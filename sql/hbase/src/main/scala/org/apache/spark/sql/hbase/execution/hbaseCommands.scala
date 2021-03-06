/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.hbase.execution

import org.apache.hadoop.fs.{Path, FileSystem}
import org.apache.hadoop.hbase.mapreduce.{LoadIncrementalHFiles, HFileOutputFormat}
import org.apache.hadoop.hbase.client.Put
import org.apache.hadoop.hbase._
import org.apache.hadoop.hbase.io.ImmutableBytesWritable
import org.apache.hadoop.mapreduce.Job
import org.apache.log4j.Logger
import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.rdd.ShuffledRDD
import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.analysis.UnresolvedRelation
import org.apache.spark.sql.catalyst.expressions.{Row, Attribute}
import org.apache.spark.sql.catalyst.plans.logical.{Subquery, LogicalPlan}
import org.apache.spark.sql.catalyst.types.DataType
import org.apache.spark.sql.execution.RunnableCommand
import org.apache.spark.sql.hbase._
import org.apache.spark.sql.sources.LogicalRelation
import scala.collection.JavaConversions._

import scala.collection.mutable.{ListBuffer, ArrayBuffer}

@DeveloperApi
case class AlterDropColCommand(tableName: String, columnName: String) extends RunnableCommand {

  def run(sqlContext: SQLContext): Seq[Row] = {
    val context = sqlContext.asInstanceOf[HBaseSQLContext]
    context.catalog.alterTableDropNonKey(tableName, columnName)
    Seq.empty[Row]
  }

  override def output: Seq[Attribute] = Seq.empty
}

@DeveloperApi
case class AlterAddColCommand(tableName: String,
                              colName: String,
                              colType: String,
                              colFamily: String,
                              colQualifier: String) extends RunnableCommand {

  def run(sqlContext: SQLContext): Seq[Row] = {
    val context = sqlContext.asInstanceOf[HBaseSQLContext]
    context.catalog.alterTableAddNonKey(tableName,
      NonKeyColumn(
        colName, context.catalog.getDataType(colType), colFamily, colQualifier)
    )
    Seq.empty[Row]
  }

  override def output: Seq[Attribute] = Seq.empty
}

@DeveloperApi
case class DropHbaseTableCommand(tableName: String) extends RunnableCommand {

  def run(sqlContext: SQLContext): Seq[Row] = {
    val context = sqlContext.asInstanceOf[HBaseSQLContext]
    context.catalog.deleteTable(tableName)
    Seq.empty[Row]
  }

  override def output: Seq[Attribute] = Seq.empty
}

@DeveloperApi
case object ShowTablesCommand extends RunnableCommand {

  def run(sqlContext: SQLContext): Seq[Row] = {
    val context = sqlContext.asInstanceOf[HBaseSQLContext]
    val buffer = new ArrayBuffer[Row]()
    val tables = context.catalog.getAllTableName
    tables.foreach(x => buffer.append(Row(x)))
    buffer.toSeq
  }

  override def output: Seq[Attribute] = Seq.empty
}

@DeveloperApi
case class DescribeTableCommand(tableName: String) extends RunnableCommand {

  def run(sqlContext: SQLContext): Seq[Row] = {
    val context = sqlContext.asInstanceOf[HBaseSQLContext]
    val buffer = new ArrayBuffer[Row]()
    val relation = context.catalog.getTable(tableName)
    if (relation.isDefined) {
      relation.get.allColumns.foreach {
        case keyColumn: KeyColumn =>
          buffer.append(Row(keyColumn.sqlName, keyColumn.dataType,
            "KEY COLUMN", keyColumn.order))
        case nonKeyColumn: NonKeyColumn =>
          buffer.append(Row(nonKeyColumn.sqlName, nonKeyColumn.dataType,
            "NON KEY COLUMN", nonKeyColumn.family, nonKeyColumn.qualifier))
      }
      buffer.toSeq
    } else {
      sys.error(s"can not find table $tableName")
    }
  }

  override def output: Seq[Attribute] = Seq.empty
}

@DeveloperApi
case class InsertValueIntoTableCommand(tableName: String, valueSeq: Seq[String])
  extends RunnableCommand {
  override def run(sqlContext: SQLContext) = {
    val solvedRelation = sqlContext.catalog.lookupRelation(None, tableName, None)
    val relation: HBaseRelation = solvedRelation.asInstanceOf[Subquery]
      .child.asInstanceOf[LogicalRelation]
      .relation.asInstanceOf[HBaseRelation]
    val buffer = ListBuffer[Byte]()
    val keyBytes = new Array[(Array[Byte], DataType)](relation.keyColumns.size)
    val valueBytes = new Array[(Array[Byte], Array[Byte],
                                Array[Byte])](relation.nonKeyColumns.size)
    val lineBuffer = HBaseKVHelper.createLineBuffer(relation.output)
    HBaseKVHelper.string2KV(valueSeq, relation, lineBuffer, keyBytes, valueBytes)
    val rowKey = HBaseKVHelper.encodingRawKeyColumns(buffer, keyBytes)
    val put = new Put(rowKey)
    valueBytes.foreach { case (family, qualifier, value) =>
      put.add(family, qualifier, value)
    }
    relation.htable.put(put)
    Seq.empty[Row]
  }

  override def output: Seq[Attribute] = Seq.empty
}

@DeveloperApi
case class BulkLoadIntoTableCommand(path: String, tableName: String,
                                    isLocal: Boolean, delimiter: Option[String])
  extends RunnableCommand {

  private[hbase] def makeBulkLoadRDD(splitKeys: Array[ImmutableBytesWritableWrapper],
                                     hadoopReader: HadoopReader, job: Job, tmpPath: String) = {
    val ordering = implicitly[Ordering[ImmutableBytesWritableWrapper]]
    val rdd = hadoopReader.makeBulkLoadRDDFromTextFile
    val partitioner = new HBasePartitioner(rdd)(splitKeys)
    // Todo: fix issues with HBaseShuffledRDD
    val shuffled =
      new ShuffledRDD[ImmutableBytesWritableWrapper, PutWrapper, PutWrapper](rdd, partitioner)
        .setKeyOrdering(ordering)
    //.setHbasePartitions(relation.partitions)
    val bulkLoadRDD = shuffled.mapPartitions { iter =>
      // the rdd now already sort by key, to sort by value
      val map = new java.util.TreeSet[KeyValue](KeyValue.COMPARATOR)
      var preKV: (ImmutableBytesWritableWrapper, PutWrapper) = null
      var nowKV: (ImmutableBytesWritableWrapper, PutWrapper) = null
      val ret = new ArrayBuffer[(ImmutableBytesWritable, KeyValue)]()
      if (iter.hasNext) {
        preKV = iter.next()
        var cellsIter = preKV._2.toPut.getFamilyCellMap.values().iterator()
        while (cellsIter.hasNext) {
          cellsIter.next().foreach { cell =>
            val kv = KeyValueUtil.ensureKeyValue(cell)
            map.add(kv)
          }
        }
        while (iter.hasNext) {
          nowKV = iter.next()
          if (0 == (nowKV._1 compareTo preKV._1)) {
            cellsIter = nowKV._2.toPut.getFamilyCellMap.values().iterator()
            while (cellsIter.hasNext) {
              cellsIter.next().foreach { cell =>
                val kv = KeyValueUtil.ensureKeyValue(cell)
                map.add(kv)
              }
            }
          } else {
            ret ++= map.iterator().map((preKV._1.toImmutableBytesWritable, _))
            preKV = nowKV
            map.clear()
            cellsIter = preKV._2.toPut.getFamilyCellMap.values().iterator()
            while (cellsIter.hasNext) {
              cellsIter.next().foreach { cell =>
                val kv = KeyValueUtil.ensureKeyValue(cell)
                map.add(kv)
              }
            }
          }
        }
        ret ++= map.iterator().map((preKV._1.toImmutableBytesWritable, _))
        map.clear()
        ret.iterator
      } else {
        Iterator.empty
      }
    }

    job.setOutputKeyClass(classOf[ImmutableBytesWritable])
    job.setOutputValueClass(classOf[KeyValue])
    job.setOutputFormatClass(classOf[HFileOutputFormat])
    job.getConfiguration.set("mapred.output.dir", tmpPath)
    bulkLoadRDD.saveAsNewAPIHadoopDataset(job.getConfiguration)
  }

  override def run(sqlContext: SQLContext) = {
    val solvedRelation = sqlContext.catalog.lookupRelation(None, tableName, None)
    val relation: HBaseRelation = solvedRelation.asInstanceOf[Subquery]
      .child.asInstanceOf[LogicalRelation]
      .relation.asInstanceOf[HBaseRelation]
    val hbContext = sqlContext.asInstanceOf[HBaseSQLContext]
    val logger = Logger.getLogger(getClass.getName)

    val conf = hbContext.sparkContext.hadoopConfiguration

    val job = Job.getInstance(conf)

    val hadoopReader = if (isLocal) {
      val fs = FileSystem.getLocal(conf)
      val pathString = fs.pathToFile(new Path(path)).getCanonicalPath
      new HadoopReader(hbContext.sparkContext, pathString, delimiter)(relation)
    } else {
      new HadoopReader(hbContext.sparkContext, path, delimiter)(relation)
    }

    // tmp path for storing HFile
    val tmpPath = Util.getTempFilePath(conf, relation.tableName)
    val splitKeys = relation.getRegionStartKeys.toArray
    logger.debug(s"Starting makeBulkLoad on table ${relation.htable.getName} ...")
    makeBulkLoadRDD(splitKeys, hadoopReader, job, tmpPath)
    val tablePath = new Path(tmpPath)
    val load = new LoadIncrementalHFiles(conf)
    logger.debug(s"Starting doBulkLoad on table ${relation.htable.getName} ...")
    load.doBulkLoad(tablePath, relation.htable)
    Seq.empty[Row]
  }

  override def output = Nil
}
