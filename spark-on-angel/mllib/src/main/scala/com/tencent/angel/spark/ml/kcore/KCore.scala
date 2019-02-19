package com.tencent.angel.spark.ml.kcore

import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel
import org.slf4j.LoggerFactory



object KCore {

  private val LOG = LoggerFactory.getLogger(this.getClass)

  def process(edges: RDD[(Int, Int)],
      partitionNum: Int,
      maxIdOption: Option[Int],
      storageLevel: StorageLevel,
      switchRate: Double = 0.001): RDD[(Int, Int)] = {
    val graph = edges.groupByKey(partitionNum).mapPartitions { part =>
      Iterator(KCoreGraphPartition.fromGroup(part))
    }.persist(storageLevel)

    val maxId = maxIdOption.getOrElse{
      graph.map(_.max).aggregate(Int.MinValue)(math.max, math.max)
    }
    val model = KCorePSModel.fromMaxId(maxId + 1)

    // init
    graph.foreach(_.init(model))
    LOG.info(s"init core sum: ${graph.map(_.sum(model)).sum()}")


    var numMsg = Long.MaxValue
    var iterNum = 0
    var version = 0

    while (numMsg > 0) {
      iterNum += 1
      version += 1
      numMsg = graph.map(_.process(model, version, numMsg < maxId *switchRate)).reduce(_ + _)
      LOG.info(s"iter-$iterNum, num node updated: $numMsg")

      // reset version
      if (Coder.isMaxVersion(version + 1)) {
        LOG.info("reset version")
        version = 0
        graph.foreach(_.resetVersion(model))
      }

      // show sum of cores every 10 iter
      if (iterNum % 10 == 0) {
        val sum = graph.map(_.sum(model)).sum()
        LOG.info(s"iter-$iterNum, core sum = $sum")
      }
    }

    LOG.info(s"iteration end in $iterNum round, final core sum is ${graph.map(_.sum(model)).sum()}")

    // save
    graph.flatMap(_.save(model))
  }
}
