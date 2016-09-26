package org.dizhang.seqspark.assoc

import breeze.linalg.{DenseMatrix, DenseVector, sum}
import org.apache.spark.SparkContext
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD
import org.dizhang.seqspark.stat._
import org.dizhang.seqspark.annot.VariantAnnotOp._
import org.dizhang.seqspark.util.{Constant, SingleStudyContext}
import org.dizhang.seqspark.util.Constant.Pheno
import org.dizhang.seqspark.util.UserConfig._
import AssocMaster._
import org.dizhang.seqspark.annot.RefGene
import org.dizhang.seqspark.ds.Counter._
import org.dizhang.seqspark.worker.Data
import org.slf4j.LoggerFactory
import org.dizhang.seqspark.geno.GeneralizedVCF._
/**
  * asymptotic test
  */
object AssocMaster {
  /** constants */
  val IntPair = CounterElementSemiGroup.PairInt
  val Permu = Constant.Permutation
  val F = Constant.Annotation.Feature
  val FuncF = Set(F.StopGain, F.StopLoss, F.SpliceSite, F.NonSynonymous)
  val IK = Constant.Variant.InfoKey
  val Imputed = (Double, Double, Double)

  def makeEncode[A](currentGenotype: Data[A],
                    y: Broadcast[DenseVector[Double]],
                    cov: Broadcast[Option[DenseMatrix[Double]]],
                    controls: Broadcast[Array[Boolean]],
                    config: MethodConfig)(implicit sc: SparkContext, cnf: RootConfig): RDD[(String, Encode)] = {

    /** this is quite tricky
      * some encoding methods require phenotype information
      * e.g. to learning weight from data */
    val sampleSize = y.value.length
    val codingScheme = config.`type`
    val annotated = codingScheme match {
      case MethodType.single =>
        currentGenotype.map(v => (s"${v.chr}:${v.pos}", v)).groupByKey()
      case MethodType.meta =>
        currentGenotype.map(v => (s"${v.chr}:${v.pos.toInt/1e6}", v)).groupByKey()
      case _ =>
        val refSeqConf = cnf.annotation.RefSeq
        val build = refSeqConf.getString("build")
        val coordFile = refSeqConf.getString("coordFile")
        val seqFile = refSeqConf.getString("seqFile")
        val refGene = sc.broadcast(RefGene(build, coordFile, seqFile))
        currentGenotype.map{v => v.annotateByGene(refGene); v}
          .map(v => (v.parseInfo(IK.gene), v))
          .groupByKey()
    }

    annotated.map(p =>
      (p._1, Encode(p._2, Option(controls.value), Option(y.value), cov.value, config))
    )
  }

  def adjustForCov(binaryTrait: Boolean,
                  y: DenseVector[Double],
                  cov: DenseMatrix[Double]): Regression = {
      if (binaryTrait) {
        LogisticRegression(y, cov)
      } else {
        LinearRegression(y, cov)
      }
  }

  def maxThisLoop(base: Int, i: Int, max: Int, bs: Int): Int = {
    val thisLoopLimit = math.pow(base, i).toInt * bs
    val partialSum = sum(0 to i map(x => math.pow(base, x).toInt)) * bs
    if (partialSum + thisLoopLimit > max)
      max - partialSum
    else
      thisLoopLimit
  }

  def expand(data: RDD[(String, Encode)], cur: Int, target: Int): RDD[(String, Encode)] = {
    if (target == cur)
      data
    else
      data.map(x => Array.fill(math.ceil(target/cur).toInt)(x)).flatMap(x => x)
  }


  def permutationTest(encode: RDD[(String, Encode)],
                      y: Broadcast[DenseVector[Double]],
                      cov: Broadcast[Option[DenseMatrix[Double]]],
                      binaryTrait: Boolean,
                      controls: Broadcast[Array[Boolean]],
                      config: MethodConfig)(implicit sc: SparkContext): Map[String, TestResult] = {
    val reg = adjustForCov(binaryTrait, y.value, cov.value.get)
    val nm = ScoreTest.NullModel(reg)
    val asymptoticRes = asymptoticTest(encode, y, cov, binaryTrait, config).collect().toMap
    val asymptoticStatistic = sc.broadcast(asymptoticRes.map(p => p._1 -> p._2.statistic))
    val sites = encode.count().toInt
    val max = Permu.max(sites)
    val min = Permu.min(sites)
    val base = Permu.base
    val batchSize = min * base
    val loops = math.round(math.log(sites)/math.log(base)).toInt
    var curEncode = encode
    var pCount = sc.broadcast(asymptoticRes.map(x => x._1 -> (0, 0)))
    for (i <- 0 to loops) {
      val lastMax = if (i == 0) batchSize else math.pow(base, i - 1).toInt * batchSize
      val curMax = maxThisLoop(base, i, max, batchSize)
      curEncode = expand(curEncode, lastMax, curMax)
      val curPCount = curEncode.map{x =>
        val ref = asymptoticStatistic.value(x._1)
        val model =
          if (config.maf.getBoolean("fixed")) {
            Burden.ResamplingTest(ref, min - pCount.value(x._1)._1, batchSize, nm, x._2)
          } else {
            VT.ResamplingTest(ref, min - pCount.value(x._1)._1, batchSize, nm, x._2)
          }
        x._1 -> model.pCount
      }.reduceByKey((a, b) => IntPair.op(a, b)).collect().toMap

      pCount = sc.broadcast(for ((k, v) <- pCount.value)
        yield if (curPCount.contains(k)) k -> IntPair.op(v, curPCount(k)) else k -> v)
      curEncode = curEncode.filter(x => pCount.value(x._1)._1 < min)
    }
    pCount.value.map(x => (x._1, TestResult(None, None, x._2._2.toDouble, x._2._1.toDouble/x._2._2)))
  }

  def asymptoticTest(encode: RDD[(String, Encode)],
                     y: Broadcast[DenseVector[Double]],
                     cov: Broadcast[Option[DenseMatrix[Double]]],
                     binaryTrait: Boolean,
                     config: MethodConfig)(implicit sc: SparkContext): RDD[(String, AssocMethod.AnalyticResult)] = {
    val reg = adjustForCov(binaryTrait, y.value, cov.value.get)
    lazy val nm = sc.broadcast(ScoreTest.NullModel(reg))
    lazy val snm = sc.broadcast(SKATO.NullModel(reg))
    config.`type` match {
      case MethodType.skat =>
        encode.map(p => (p._1, SKAT(nm.value, p._2).result))
      case MethodType.skato =>
        encode.map(p => (p._1, SKATO(snm.value, p._2).result))
      case _ =>
        if (config.maf.getBoolean("fixed"))
          encode.map(p => (p._1, Burden.AnalyticTest(nm.value, p._2).result))
        else
          encode.map(p => (p._1, VT.AnalyticTest(nm.value, p._2).result))
    }
  }

  def rareMetalWorker(encode: RDD[(String, Encode)],
                      y: Broadcast[DenseVector[Double]],
                      cov: Broadcast[Option[DenseMatrix[Double]]],
                      binaryTrait: Boolean,
                      config: MethodConfig)(implicit sc: SparkContext): Unit = {
    val reg = adjustForCov(binaryTrait, y.value, cov.value.get)
    val nm = sc.broadcast(ScoreTest.NullModel(reg))
    encode.map(p => (p._1, RareMetalWorker.Analytic(nm.value, p._2).result))
  }

  def runTraitWithMethod[A](currentGenotype: Data[A],
                         currentTrait: (String, Broadcast[DenseVector[Double]]),
                         cov: Broadcast[Option[DenseMatrix[Double]]],
                         controls: Broadcast[Array[Boolean]],
                         method: String)(implicit sc: SparkContext, cnf: RootConfig): Unit = {
    val config = cnf.association
    val methodConfig = config.method(method)
    val encode = makeEncode(currentGenotype, currentTrait._2, cov, controls, methodConfig)
    val permutation = methodConfig.resampling
    val test = methodConfig.test
    val binary = config.`trait`(currentTrait._1).binary

    if (methodConfig.`type` == MethodType.meta) {
      rareMetalWorker(encode, currentTrait._2, cov, binary, methodConfig)
    } else if (permutation) {
      permutationTest(encode, currentTrait._2, cov, binary, controls, methodConfig)
    } else {
      asymptoticTest(encode, currentTrait._2, cov, binary, methodConfig)
    }
  }
}

class AssocMaster[A](genotype: Data[A])
                 (implicit ssc: SingleStudyContext) extends {

  val cnf = ssc.userConfig
  val sc = ssc.sparkContext
  val phenotype = ssc.phenotype

  val logger = LoggerFactory.getLogger(this.getClass)

  val assocConf = cnf.association

  def run(): Unit = {
    val traits = assocConf.traitList
    traits.foreach{
      t => runTrait(t)
    }
  }

  def runTrait(traitName: String) = {
    try {
      logger.info(s"load trait $traitName from phenotype database")
      phenotype.getTrait(traitName) match {
        case Left(msg) => logger.warn(s"getting trait $traitName failed, skip")
        case Right(tr) =>
          val currentTrait = (traitName, sc.broadcast(tr))
          val methods = assocConf.methodList
          val indicator = sc.broadcast(phenotype.indicate(traitName))
          val controls = sc.broadcast(phenotype.select(Pheno.Header.control).zip(indicator.value)
            .filter(p => p._2).map(p => if (p._1.get == "1") true else false))
          val currentGenotype = genotype.map(v => v.select(indicator.value))
          val traitConfig = assocConf.`trait`(traitName)
          val currentCov = sc.broadcast(
            if (traitConfig.covariates.nonEmpty) {
              phenotype.getCov(traitName, traitConfig.covariates, Array.fill(traitConfig.covariates.length)(0.05)) match {
                case Left(msg) =>
                  logger.error(s"failed getting covariates for $traitName, use None")
                  None
                case Right(dm) => Some(dm)
              }
            } else
              None)
          methods.foreach(m => runTraitWithMethod(currentGenotype, currentTrait, currentCov, controls, m))
      }
    } catch {
      case e: Exception => logger.warn(e.getStackTrace.toString)
    }
  }
}