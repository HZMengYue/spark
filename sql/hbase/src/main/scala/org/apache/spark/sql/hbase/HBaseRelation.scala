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
package org.apache.spark.sql.hbase

import java.util

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.hbase.HBaseConfiguration
import org.apache.hadoop.hbase.client.{Get, HTable, Put, Result, Scan}
import org.apache.hadoop.hbase.filter._
import org.apache.hadoop.hbase.util.Bytes
import org.apache.log4j.Logger
import org.apache.spark.Partition
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.types._
import org.apache.spark.sql.hbase.catalyst.NotPusher
import org.apache.spark.sql.hbase.catalyst.expressions.PartialPredicateOperations._
import org.apache.spark.sql.hbase.catalyst.types.PartitionRange
import org.apache.spark.sql.sources.{LogicalRelation, CatalystScan, RelationProvider, BaseRelation}

import scala.collection.JavaConverters._
import scala.collection.mutable.{ArrayBuffer, ListBuffer}

class HBaseSource extends RelationProvider {
  // Returns a new HBase relation with the given parameters
  override def createRelation(
    sqlContext: SQLContext,
    parameters: Map[String, String]): BaseRelation = {
    val context = sqlContext.asInstanceOf[HBaseSQLContext]
    val catalog = context.catalog

    val tableName = parameters("tableName")
    val rawNamespace = parameters("namespace")
    val namespace: Option[String] = if (rawNamespace == null || rawNamespace.isEmpty) None
                                    else Some(rawNamespace)
    val hbaseTable = parameters("hbaseTableName")
    val colsSeq = parameters("colsSeq").split(",")
    val keyCols = parameters("keyCols").split(";")
      .map{case c => val cols = c.split(","); (cols(0), cols(1))}
    val nonKeyCols = parameters("nonKeyCols").split(";")
      .filterNot(_ == "")
      .map{case c => val cols = c.split(","); (cols(0), cols(1), cols(2), cols(3))}

    val keyMap:Map[String, String] = keyCols.toMap
    val allColumns = colsSeq.map {
      case name =>
        if (keyMap.contains(name)) {
          KeyColumn(
            name,
            catalog.getDataType(keyMap.get(name).get),
            keyCols.indexWhere(_._1 == name))
        } else {
          val nonKeyCol = nonKeyCols.find(_._1 == name).get
          NonKeyColumn(
            name,
            catalog.getDataType(nonKeyCol._2),
            nonKeyCol._3,
            nonKeyCol._4
          )
        }
    }
    catalog.createTable(tableName, rawNamespace, hbaseTable, allColumns, null)
  }
}

@SerialVersionUID(1529873946227428789L)
private[hbase] case class HBaseRelation(
    tableName: String,
    hbaseNamespace: String,
    hbaseTableName: String,
    allColumns: Seq[AbstractColumn])(@transient var context: HBaseSQLContext)
  extends CatalystScan with Serializable {

  @transient lazy val logger = Logger.getLogger(getClass.getName)

  @transient lazy val keyColumns = allColumns.filter(_.isInstanceOf[KeyColumn])
    .asInstanceOf[Seq[KeyColumn]].sortBy(_.order)

  @transient lazy val nonKeyColumns = allColumns.filter(_.isInstanceOf[NonKeyColumn])
    .asInstanceOf[Seq[NonKeyColumn]]

  lazy val partitionKeys = keyColumns.map(col=>
                   logicalRelation.output.find(_.name.equals(col.sqlName)).get)

  @transient lazy val columnMap = allColumns.map {
    case key: KeyColumn => (key.sqlName, key.order)
    case nonKey: NonKeyColumn => (nonKey.sqlName, nonKey)
  }.toMap

  allColumns.zipWithIndex.foreach(pi=> pi._1.ordinal = pi._2)

  private var serializedConfiguration: Array[Byte] = _

  def setConfig(inconfig: Configuration) = {
    config = inconfig
    if (inconfig != null) {
      serializedConfiguration = Util.serializeHBaseConfiguration(inconfig)
    }
  }

  @transient var config: Configuration = _

  def configuration() = getConf

  private def getConf: Configuration = {
    if (config == null) {
      config = {
        if (serializedConfiguration != null) {
          Util.deserializeHBaseConfiguration(serializedConfiguration)
        }
        else {
          HBaseConfiguration.create
        }
      }
    }
    config
  }

  logger.debug(s"HBaseRelation config has zkPort="
    + s"${getConf.get("hbase.zookeeper.property.clientPort")}")

  @transient lazy val htable: HTable = new HTable(getConf, hbaseTableName)

  def isNonKey(attr: AttributeReference): Boolean = {
    keyIndex(attr) < 0
  }

  def keyIndex(attr: AttributeReference): Int = {
    // -1 if nonexistent
    partitionKeys.indexWhere(_.exprId == attr.exprId)
  }

  // find the index in a sequence of AttributeReferences that is a key; -1 if not present
  def rowIndex(refs: Seq[Attribute], keyIndex: Int): Int = {
    refs.indexWhere(_.exprId == partitionKeys(keyIndex).exprId)
  }

  def closeHTable() = htable.close()

  // corresponding logical relation
  @transient lazy val logicalRelation = LogicalRelation(this)

  lazy val output = logicalRelation.output

  lazy val partitions: Seq[HBasePartition] = {
    val regionLocations = htable.getRegionLocations.asScala.toSeq
    logger.info(s"Number of HBase regions for " +
      s"table ${htable.getName.getNameAsString}: ${regionLocations.size}")
    regionLocations.zipWithIndex.map {
      case p =>
        val start: Option[HBaseRawType] = {
          if (p._1._1.getStartKey.length == 0)
          {
            None
          } else {
            Some(p._1._1.getStartKey)
          }
        }
        val end: Option[HBaseRawType] = {
          if (p._1._1.getEndKey.length == 0) {
            None
          } else {
            Some(p._1._1.getEndKey)
          }
        }
        new HBasePartition(
          p._2, p._2, -1,
          start,
          end,
          Some(p._1._2.getHostname))
    }
  }

  private[hbase] def generateRange(partition: HBasePartition, pred: Expression,
                            index: Int): (PartitionRange[_]) = {
    def getData(dt: NativeType,
                buffer: ListBuffer[HBaseRawType],
                aBuffer: ArrayBuffer[Byte],
                bound: Option[HBaseRawType]): Option[Any] = {
      if (bound.isEmpty) None
      else {
        Some(DataTypeUtils.bytesToData(
          HBaseKVHelper.decodingRawKeyColumns(buffer, aBuffer, bound.get, keyColumns)(index),
          dt).asInstanceOf[dt.JvmType])
      }
    }

    val dt = keyColumns(index).dataType.asInstanceOf[NativeType]
    val isLastKeyIndex = index == (keyColumns.size - 1)
    val buffer = ListBuffer[HBaseRawType]()
    val aBuffer = ArrayBuffer[Byte]()
    val start = getData(dt, buffer, aBuffer, partition.lowerBound)
    val end = getData(dt, buffer, aBuffer, partition.upperBound)
    val startInclusive = start.nonEmpty
    val endInclusive = end.nonEmpty && !isLastKeyIndex
    new PartitionRange(start, startInclusive, end, endInclusive, partition.index, dt, pred)
  }

  private def prePruneRanges(ranges: Seq[PartitionRange[_]], keyIndex: Int)
  : (Seq[PartitionRange[_]], Seq[PartitionRange[_]]) = {
    require(keyIndex < keyColumns.size, "key index out of range")
    if (ranges.isEmpty) {
      (ranges, Nil)
    } else if (keyIndex == 0) {
      (Nil, ranges)
    } else {
      // the first portion is of those ranges of equal start and end values of the
      // previous dimensions so they can be subject to further checks on the next dimension
      val (p1, p2) = ranges.partition(p => p.start == p.end)
      (p2, p1.map(p => generateRange(partitions(p.id), p.pred, keyIndex)))
    }
  }

  def getPrunedPartitions(partitionPred: Option[Expression] = None): Option[Seq[HBasePartition]] = {
    def getPrunedRanges(pred: Expression): Seq[PartitionRange[_]] = {
      val predRefs = pred.references.toSeq
      val boundPruningPred = BindReferences.bindReference(pred, predRefs)
      val keyIndexToPredIndex = (for {
        (keyColumn, keyIndex) <- keyColumns.zipWithIndex
        (predRef, predIndex) <- predRefs.zipWithIndex
        if keyColumn.sqlName == predRef.name
      } yield (keyIndex, predIndex)).toMap

      val row = new GenericMutableRow(predRefs.size)
      var notPrunedRanges = partitions.map(generateRange(_, null, 0))
      var prunedRanges: Seq[PartitionRange[_]] = Nil

      for (keyIndex <- 0 until keyColumns.size; if notPrunedRanges.nonEmpty) {
        val (passedRanges, toBePrunedRanges) = prePruneRanges(notPrunedRanges, keyIndex)
        prunedRanges = prunedRanges ++ passedRanges
        notPrunedRanges =
          if (keyIndexToPredIndex.contains(keyIndex)) {
            toBePrunedRanges.filter(
              range => {
                val predIndex = keyIndexToPredIndex(keyIndex)
                row.update(predIndex, range)
                val partialEvalResult = boundPruningPred.partialEval(row)
                // MAYBE is represented by a null
                (partialEvalResult == null) || partialEvalResult.asInstanceOf[Boolean]
              }
            )
          } else toBePrunedRanges
      }
      prunedRanges ++ notPrunedRanges
    }

    partitionPred match {
      case None => Some(partitions)
      case Some(pred) => if (pred.references.intersect(AttributeSet(partitionKeys)).isEmpty) {
        Some(partitions)
      } else {
        val prunedRanges: Seq[PartitionRange[_]] = getPrunedRanges(pred)
        var idx: Int = -1
        val result = Some(prunedRanges.map(p => {
          val par = partitions(p.id)
          idx = idx + 1
          if (p.pred == null) {
            new HBasePartition(idx, par.mappedIndex, -1, par.lowerBound, par.upperBound, par.server)
          } else {
            new HBasePartition(idx, par.mappedIndex, -1, par.lowerBound, par.upperBound,
              par.server, Some(p.pred))
          }
        }))
        result
      }
    }
  }

  def getPrunedPartitions2(partitionPred: Option[Expression] = None)
  : Option[Seq[HBasePartition]] = {
    def getPrunedRanges(pred: Expression): Seq[PartitionRange[_]] = {
      val predRefs = pred.references.toSeq
      val boundPruningPred = BindReferences.bindReference(pred, predRefs)
      val keyIndexToPredIndex = (for {
        (keyColumn, keyIndex) <- keyColumns.zipWithIndex
        (predRef, predIndex) <- predRefs.zipWithIndex
        if keyColumn.sqlName == predRef.name
      } yield (keyIndex, predIndex)).toMap

      val row = new GenericMutableRow(predRefs.size)
      var notPrunedRanges = partitions.map(generateRange(_, boundPruningPred, 0))
      var prunedRanges: Seq[PartitionRange[_]] = Nil

      for (keyIndex <- 0 until keyColumns.size; if notPrunedRanges.nonEmpty) {
        val (passedRanges, toBePrunedRanges) = prePruneRanges(notPrunedRanges, keyIndex)
        prunedRanges = prunedRanges ++ passedRanges
        notPrunedRanges =
          if (keyIndexToPredIndex.contains(keyIndex)) {
            toBePrunedRanges.filter(
              range => {
                val predIndex = keyIndexToPredIndex(keyIndex)
                row.update(predIndex, range)
                val partialEvalResult = range.pred.partialReduce(row, predRefs)
                range.pred = if (partialEvalResult._1 == null) {
                  // progressively fine tune the constraining predicate
                  partialEvalResult._2
                } else {
                  null
                }
                // MAYBE is represented by a to-be-qualified-with expression
                partialEvalResult._1 == null ||
                partialEvalResult._1.asInstanceOf[Boolean]
              }
            )
          } else toBePrunedRanges
      }
      prunedRanges ++ notPrunedRanges
    }

    partitionPred match {
      case None => Some(partitions)
      case Some(pred) => if (pred.references.intersect(AttributeSet(partitionKeys)).isEmpty) {
        // the predicate does not apply to the partitions at all; just push down the filtering
        Some(partitions.map(p => new HBasePartition(p.idx, p.mappedIndex, p.keyPartialEvalIndex,
          p.lowerBound, p.upperBound, p.server, Some(pred))))
      } else {
        val prunedRanges: Seq[PartitionRange[_]] = getPrunedRanges(pred)
        var idx: Int = -1
        val result = Some(prunedRanges.map(p => {
          val par = partitions(p.id)
          idx = idx + 1
          // pruned partitions have the same "physical" partition index, but different
          // "canonical" index
          if (p.pred == null) {
            new HBasePartition(idx, par.mappedIndex, par.keyPartialEvalIndex, par.lowerBound,
              par.upperBound, par.server, None)
          } else {
            new HBasePartition(idx, par.mappedIndex, par.keyPartialEvalIndex, par.lowerBound,
              par.upperBound, par.server, Some(p.pred))
          }
        }))
        result
      }
    }
  }

  /**
   * Return the start keys of all of the regions in this table,
   * as a list of SparkImmutableBytesWritable.
   */
  def getRegionStartKeys = {
    val byteKeys: Array[Array[Byte]] = htable.getStartKeys
    val ret = ArrayBuffer[ImmutableBytesWritableWrapper]()
    for (byteKey <- byteKeys) {
      ret += new ImmutableBytesWritableWrapper(byteKey)
    }
    ret
  }

  def buildFilter(
                   projList: Seq[NamedExpression],
                   rowKeyPredicate: Option[Expression],
                   valuePredicate: Option[Expression]) = {
    val distinctProjList = projList.distinct
    if (distinctProjList.size == allColumns.size) {
      Option(new FilterList(new util.ArrayList[Filter]))
    } else {
      val filtersList: List[Filter] = nonKeyColumns.filter {
        case nkc => distinctProjList.exists(nkc.sqlName == _.name)
      }.map {
        case nkc@NonKeyColumn(_, _, family, qualifier) =>
          val columnFilters = new util.ArrayList[Filter]
          columnFilters.add(
            new FamilyFilter(
              CompareFilter.CompareOp.EQUAL,
              new BinaryComparator(nkc.familyRaw)
            ))
          columnFilters.add(
            new QualifierFilter(
              CompareFilter.CompareOp.EQUAL,
              new BinaryComparator(nkc.qualifierRaw)
            ))
          new FilterList(FilterList.Operator.MUST_PASS_ALL, columnFilters)
      }.toList

      Option(new FilterList(FilterList.Operator.MUST_PASS_ONE, filtersList.asJava))
    }
  }

  def buildFilter2(
                    projList: Seq[NamedExpression],
                    pred: Option[Expression]): (Option[FilterList], Option[Expression]) = {
    var distinctProjList = projList.distinct
    if (pred.isDefined) {
      distinctProjList = distinctProjList.filterNot(_.references.subsetOf(pred.get.references))
    }
    val projFilterList = if (distinctProjList.size == allColumns.size) {
      None
    } else {
      val filtersList: List[Filter] = nonKeyColumns.filter {
        case nkc => distinctProjList.exists(nkc.sqlName == _.name)
      }.map {
        case nkc@NonKeyColumn(_, _, family, qualifier) =>
          val columnFilters = new util.ArrayList[Filter]
          columnFilters.add(
            new FamilyFilter(
              CompareFilter.CompareOp.EQUAL,
              new BinaryComparator(nkc.familyRaw)
            ))
          columnFilters.add(
            new QualifierFilter(
              CompareFilter.CompareOp.EQUAL,
              new BinaryComparator(nkc.qualifierRaw)
            ))
          new FilterList(FilterList.Operator.MUST_PASS_ALL, columnFilters)
      }.toList

      Option(new FilterList(FilterList.Operator.MUST_PASS_ONE, filtersList.asJava))
    }

    if (pred.isDefined) {
      val predExp: Expression = pred.get
      // build pred pushdown filters:
      // 1. push any NOT through AND/OR
      val notPushedPred = NotPusher(predExp)
      // 2. classify the transformed predicate into pushdownable and non-pushdownable predicates
      val classier = new ScanPredClassifier(this, 0) // Right now only on primary key dimension
      val (pushdownFilterPred, otherPred) = classier(notPushedPred)
      // 3. build a FilterList mirroring the pushdownable predicate
      val predPushdownFilterList = buildFilterListFromPred(pushdownFilterPred)
      // 4. merge the above FilterList with the one from the projection
      (predPushdownFilterList, otherPred)
    } else {
      (projFilterList, None)
    }
  }

  private def buildFilterListFromPred(pred: Option[Expression]): Option[FilterList] = {
    var result: Option[FilterList] = None
    val filters = new util.ArrayList[Filter]
    if (pred.isDefined) {
      val expression = pred.get
      expression match {
        case And(left, right) =>
          if (left != null) {
            val leftFilterList = buildFilterListFromPred(Some(left))
            if (leftFilterList.isDefined) {
              filters.add(leftFilterList.get)
            }
          }
          if (right != null) {
            val rightFilterList = buildFilterListFromPred(Some(right))
            if (rightFilterList.isDefined) {
              filters.add(rightFilterList.get)
            }
          }
          result = Option(new FilterList(FilterList.Operator.MUST_PASS_ALL, filters))
        case Or(left, right) =>
          if (left != null) {
            val leftFilterList = buildFilterListFromPred(Some(left))
            if (leftFilterList.isDefined) {
              filters.add(leftFilterList.get)
            }
          }
          if (right != null) {
            val rightFilterList = buildFilterListFromPred(Some(right))
            if (rightFilterList.isDefined) {
              filters.add(rightFilterList.get)
            }
          }
          result = Option(new FilterList(FilterList.Operator.MUST_PASS_ONE, filters))
        case GreaterThan(left: AttributeReference, right: Literal) =>
          val keyColumn = keyColumns.find((p: KeyColumn) => p.sqlName.equals(left.name))
          val nonKeyColumn = nonKeyColumns.find((p: NonKeyColumn) => p.sqlName.equals(left.name))
          if (keyColumn.isDefined) {
            val filter = new RowFilter(CompareFilter.CompareOp.GREATER,
              new BinaryPrefixComparator(DataTypeUtils.dataToBytes(right.value, right.dataType)))
            result = Option(new FilterList(filter))
          } else if (nonKeyColumn.isDefined) {
            val column = nonKeyColumn.get
            val filter = new SingleColumnValueFilter(column.familyRaw,
              column.qualifierRaw,
              CompareFilter.CompareOp.GREATER,
              DataTypeUtils.getComparator(BytesUtils.create(right.dataType), right))
            result = Option(new FilterList(filter))
          }
        case GreaterThan(left: Literal, right: AttributeReference) =>
          val keyColumn = keyColumns.find((p: KeyColumn) => p.sqlName.equals(right.name))
          val nonKeyColumn = nonKeyColumns.find((p: NonKeyColumn) => p.sqlName.equals(right.name))
          if (keyColumn.isDefined) {
            val filter = new RowFilter(CompareFilter.CompareOp.GREATER,
              new BinaryPrefixComparator(DataTypeUtils.dataToBytes(left.value, left.dataType)))
            result = Option(new FilterList(filter))
          } else if (nonKeyColumn.isDefined) {
            val column = nonKeyColumn.get
            val filter = new SingleColumnValueFilter(column.familyRaw,
              column.qualifierRaw,
              CompareFilter.CompareOp.GREATER,
              DataTypeUtils.getComparator(BytesUtils.create(left.dataType), left))
            result = Option(new FilterList(filter))
          }
        case GreaterThanOrEqual(left: AttributeReference, right: Literal) =>
          val keyColumn = keyColumns.find((p: KeyColumn) => p.sqlName.equals(left.name))
          val nonKeyColumn = nonKeyColumns.find((p: NonKeyColumn) => p.sqlName.equals(left.name))
          if (keyColumn.isDefined) {
            val filter = new RowFilter(CompareFilter.CompareOp.GREATER_OR_EQUAL,
              new BinaryPrefixComparator(DataTypeUtils.dataToBytes(right.value, right.dataType)))
            result = Option(new FilterList(filter))
          } else if (nonKeyColumn.isDefined) {
            val column = nonKeyColumn.get
            val filter = new SingleColumnValueFilter(column.familyRaw,
              column.qualifierRaw,
              CompareFilter.CompareOp.GREATER_OR_EQUAL,
              DataTypeUtils.getComparator(BytesUtils.create(right.dataType), right))
            result = Option(new FilterList(filter))
          }
        case GreaterThanOrEqual(left: Literal, right: AttributeReference) =>
          val keyColumn = keyColumns.find((p: KeyColumn) => p.sqlName.equals(right.name))
          val nonKeyColumn = nonKeyColumns.find((p: NonKeyColumn) => p.sqlName.equals(right.name))
          if (keyColumn.isDefined) {
            val filter = new RowFilter(CompareFilter.CompareOp.GREATER_OR_EQUAL,
              new BinaryPrefixComparator(DataTypeUtils.dataToBytes(left.value, left.dataType)))
            result = Option(new FilterList(filter))
          } else if (nonKeyColumn.isDefined) {
            val column = nonKeyColumn.get
            val filter = new SingleColumnValueFilter(column.familyRaw,
              column.qualifierRaw,
              CompareFilter.CompareOp.GREATER_OR_EQUAL,
              DataTypeUtils.getComparator(BytesUtils.create(left.dataType), left))
            result = Option(new FilterList(filter))
          }
        case EqualTo(left: AttributeReference, right: Literal) =>
          val keyColumn = keyColumns.find((p: KeyColumn) => p.sqlName.equals(left.name))
          val nonKeyColumn = nonKeyColumns.find((p: NonKeyColumn) => p.sqlName.equals(left.name))
          if (keyColumn.isDefined) {
            val filter = new RowFilter(CompareFilter.CompareOp.EQUAL,
              new BinaryPrefixComparator(DataTypeUtils.dataToBytes(right.value, right.dataType)))
            result = Option(new FilterList(filter))
          } else if (nonKeyColumn.isDefined) {
            val column = nonKeyColumn.get
            val filter = new SingleColumnValueFilter(column.familyRaw,
              column.qualifierRaw,
              CompareFilter.CompareOp.EQUAL,
              DataTypeUtils.getComparator(BytesUtils.create(right.dataType), right))
            result = Option(new FilterList(filter))
          }
        case EqualTo(left: Literal, right: AttributeReference) =>
          val keyColumn = keyColumns.find((p: KeyColumn) => p.sqlName.equals(right.name))
          val nonKeyColumn = nonKeyColumns.find((p: NonKeyColumn) => p.sqlName.equals(right.name))
          if (keyColumn.isDefined) {
            val filter = new RowFilter(CompareFilter.CompareOp.EQUAL,
              new BinaryPrefixComparator(DataTypeUtils.dataToBytes(left.value, left.dataType)))
            result = Option(new FilterList(filter))
          } else if (nonKeyColumn.isDefined) {
            val column = nonKeyColumn.get
            val filter = new SingleColumnValueFilter(column.familyRaw,
              column.qualifierRaw,
              CompareFilter.CompareOp.EQUAL,
              DataTypeUtils.getComparator(BytesUtils.create(left.dataType), left))
            result = Option(new FilterList(filter))
          }
        case LessThan(left: AttributeReference, right: Literal) =>
          val keyColumn = keyColumns.find((p: KeyColumn) => p.sqlName.equals(left.name))
          val nonKeyColumn = nonKeyColumns.find((p: NonKeyColumn) => p.sqlName.equals(left.name))
          if (keyColumn.isDefined) {
            val filter = new RowFilter(CompareFilter.CompareOp.LESS,
              new BinaryPrefixComparator(DataTypeUtils.dataToBytes(right.value, right.dataType)))
            result = Option(new FilterList(filter))
          } else if (nonKeyColumn.isDefined) {
            val column = nonKeyColumn.get
            val filter = new SingleColumnValueFilter(column.familyRaw,
              column.qualifierRaw,
              CompareFilter.CompareOp.LESS,
              DataTypeUtils.getComparator(BytesUtils.create(right.dataType), right))
            result = Option(new FilterList(filter))
          }
        case LessThan(left: Literal, right: AttributeReference) =>
          val keyColumn = keyColumns.find((p: KeyColumn) => p.sqlName.equals(right.name))
          val nonKeyColumn = nonKeyColumns.find((p: NonKeyColumn) => p.sqlName.equals(right.name))
          if (keyColumn.isDefined) {
            val filter = new RowFilter(CompareFilter.CompareOp.LESS,
              new BinaryPrefixComparator(DataTypeUtils.dataToBytes(left.value, left.dataType)))
            result = Option(new FilterList(filter))
          } else if (nonKeyColumn.isDefined) {
            val column = nonKeyColumn.get
            val filter = new SingleColumnValueFilter(column.familyRaw,
              column.qualifierRaw,
              CompareFilter.CompareOp.LESS,
              DataTypeUtils.getComparator(BytesUtils.create(left.dataType), left))
            result = Option(new FilterList(filter))
          }
        case LessThanOrEqual(left: AttributeReference, right: Literal) =>
          val keyColumn = keyColumns.find((p: KeyColumn) => p.sqlName.equals(left.name))
          val nonKeyColumn = nonKeyColumns.find((p: NonKeyColumn) => p.sqlName.equals(left.name))
          if (keyColumn.isDefined) {
            val filter = new RowFilter(CompareFilter.CompareOp.LESS_OR_EQUAL,
              new BinaryPrefixComparator(DataTypeUtils.dataToBytes(right.value, right.dataType)))
            result = Option(new FilterList(filter))
          } else if (nonKeyColumn.isDefined) {
            val column = nonKeyColumn.get
            val filter = new SingleColumnValueFilter(column.familyRaw,
              column.qualifierRaw,
              CompareFilter.CompareOp.LESS_OR_EQUAL,
              DataTypeUtils.getComparator(BytesUtils.create(right.dataType), right))
            result = Option(new FilterList(filter))
          }
        case LessThanOrEqual(left: Literal, right: AttributeReference) =>
          val keyColumn = keyColumns.find((p: KeyColumn) => p.sqlName.equals(right.name))
          val nonKeyColumn = nonKeyColumns.find((p: NonKeyColumn) => p.sqlName.equals(right.name))
          if (keyColumn.isDefined) {
            val filter = new RowFilter(CompareFilter.CompareOp.LESS_OR_EQUAL,
              new BinaryPrefixComparator(DataTypeUtils.dataToBytes(left.value, left.dataType)))
            result = Option(new FilterList(filter))
          } else if (nonKeyColumn.isDefined) {
            val column = nonKeyColumn.get
            val filter = new SingleColumnValueFilter(column.familyRaw,
              column.qualifierRaw,
              CompareFilter.CompareOp.LESS_OR_EQUAL,
              DataTypeUtils.getComparator(BytesUtils.create(left.dataType), left))
            result = Option(new FilterList(filter))
          }
      }
    }
    result
  }

  def buildPut(row: Row): Put = {
    // TODO: revisit this using new KeyComposer
    val rowKey: HBaseRawType = null
    new Put(rowKey)
  }

  def sqlContext = context
  def schema: StructType = StructType(allColumns.map(c => c match {
    case KeyColumn(name, dt, _) => StructField(name, dt, nullable = false)
    case NonKeyColumn(name, dt, _,_) => StructField(name, dt, nullable = true)
  }))

  def buildScan(requiredColumns: Seq[Attribute], filters: Seq[Expression]): RDD[Row] = {
    val filterPredicate = if (filters.isEmpty) None
                          else Some(filters(0))
    new HBaseSQLReaderRDD(
      this,
      context.codegenEnabled,
      requiredColumns,
      filterPredicate, // PartitionPred : Option[Expression]
      None, // coprocSubPlan: SparkPlan
      context
    )
  }

  def buildScan(
                 split: Partition,
                 filters: Option[FilterList],
                 projList: Seq[NamedExpression]): Scan = {
    val hbPartition = split.asInstanceOf[HBasePartition]
    val scan = {
      (hbPartition.lowerBound, hbPartition.upperBound) match {
        case (Some(lb), Some(ub)) => new Scan(lb, ub)
        case (Some(lb), None) => new Scan(lb)
        case (None, Some(ub)) => new Scan(Array[Byte](), ub)
        case _ => new Scan
      }
    }
    if (filters.isDefined && !filters.get.getFilters.isEmpty) {
      scan.setFilter(filters.get)
    }
    // TODO: add add Family to SCAN from projections
    scan
  }

  def buildGet(projList: Seq[NamedExpression], rowKey: HBaseRawType) {
    new Get(rowKey)
    // TODO: add columns to the Get
  }

  //  /**
  //   * Trait for RowKeyParser's that convert a raw array of bytes into their constituent
  //   * logical column values
  //   *
  //   */
  //  trait AbstractRowKeyParser {
  //
  ////    def createKey(rawBytes: Seq[HBaseRawType], version: Byte): HBaseRawType
  ////
  ////    def parseRowKey(rowKey: HBaseRawType): Seq[HBaseRawType]
  ////
  ////    def parseRowKeyWithMetaData(rkCols: Seq[KeyColumn], rowKey: HBaseRawType)
  ////    : SortedMap[TableName, (KeyColumn, Any)] // TODO change Any
  //  }
  //
  //  case class RowKeySpec(offsets: Seq[Int], version: Byte = RowKeyParser.Version1)
  //
  //  // TODO(Bo): replace the implementation with the null-byte terminated string logic
  //  object RowKeyParser extends AbstractRowKeyParser with Serializable {
  //    val Version1 = 1.toByte
  //    val VersionFieldLen = 1
  //    // Length in bytes of the RowKey version field
  //    val DimensionCountLen = 1
  //    // One byte for the number of key dimensions
  //    val MaxDimensions = 255
  //    val OffsetFieldLen = 2
  //
  //    // Two bytes for the value of each dimension offset.
  //    // Therefore max size of rowkey is 65535.  Note: if longer rowkeys desired in future
  //    // then simply define a new RowKey version to support it. Otherwise would be wasteful
  //    // to define as 4 bytes now.
  //    def computeLength(keys: Seq[HBaseRawType]) = {
  //      VersionFieldLen + keys.map(_.length).sum +
  //        OffsetFieldLen * keys.size + DimensionCountLen
  //    }
  //
  //    override def createKey(keys: Seq[HBaseRawType], version: Byte = Version1): HBaseRawType = {
  //      val barr = new Array[Byte](computeLength(keys))
  //      val arrayx = new AtomicInteger(0)
  //      barr(arrayx.getAndAdd(VersionFieldLen)) = version // VersionByte
  //
  //      // Remember the starting offset of first data value
  //      val valuesStartIndex = new AtomicInteger(arrayx.get)
  //
  //      // copy each of the dimension values in turn
  //      keys.foreach { k => copyToArr(barr, k, arrayx.getAndAdd(k.length))}
  //
  //      // Copy the offsets of each dim value
  //      // The valuesStartIndex is the location of the first data value and thus the first
  //      // value included in the Offsets sequence
  //      keys.foreach { k =>
  //        copyToArr(barr,
  //          short2b(valuesStartIndex.getAndAdd(k.length).toShort),
  //          arrayx.getAndAdd(OffsetFieldLen))
  //      }
  //      barr(arrayx.get) = keys.length.toByte // DimensionCountByte
  //      barr
  //    }
  //
  //    def copyToArr[T](a: Array[T], b: Array[T], aoffset: Int) = {
  //      b.copyToArray(a, aoffset)
  //    }
  //
  //    def short2b(sh: Short): Array[Byte] = {
  //      val barr = Array.ofDim[Byte](2)
  //      barr(0) = ((sh >> 8) & 0xff).toByte
  //      barr(1) = (sh & 0xff).toByte
  //      barr
  //    }
  //
  //    def b2Short(barr: Array[Byte]) = {
  //      val out = (barr(0).toShort << 8) | barr(1).toShort
  //      out
  //    }
  //
  //    def createKeyFromCatalystRow(schema: StructType, keyCols: Seq[KeyColumn], row: Row) = {
  //      //      val rawKeyCols = DataTypeUtils.catalystRowToHBaseRawVals(schema, row, keyCols)
  //      //      createKey(rawKeyCols)
  //      null
  //    }
  //
  //    def getMinimumRowKeyLength = VersionFieldLen + DimensionCountLen
  //
  //    override def parseRowKey(rowKey: HBaseRawType): Seq[HBaseRawType] = {
  //      assert(rowKey.length >= getMinimumRowKeyLength,
  //        s"RowKey is invalid format - less than minlen . Actual length=${rowKey.length}")
  //      assert(rowKey(0) == Version1, s"Only Version1 supported. Actual=${rowKey(0)}")
  //      val ndims: Int = rowKey(rowKey.length - 1).toInt
  //      val offsetsStart = rowKey.length - DimensionCountLen - ndims * OffsetFieldLen
  //      val rowKeySpec = RowKeySpec(
  //        for (dx <- 0 to ndims - 1)
  //        yield b2Short(rowKey.slice(offsetsStart + dx * OffsetFieldLen,
  //          offsetsStart + (dx + 1) * OffsetFieldLen))
  //      )
  //
  //      val endOffsets = rowKeySpec.offsets.tail :+ (rowKey.length - DimensionCountLen - 1)
  //      val colsList = rowKeySpec.offsets.zipWithIndex.map { case (off, ix) =>
  //        rowKey.slice(off, endOffsets(ix))
  //      }
  //      colsList
  //    }
  //
  ////    //TODO
  ////    override def parseRowKeyWithMetaData(rkCols: Seq[KeyColumn], rowKey: HBaseRawType):
  ////    SortedMap[TableName, (KeyColumn, Any)] = {
  ////      import scala.collection.mutable.HashMap
  ////
  ////      val rowKeyVals = parseRowKey(rowKey)
  ////      val rmap = rowKeyVals.zipWithIndex.foldLeft(new HashMap[ColumnName, (Column, Any)]()) {
  ////        case (m, (cval, ix)) =>
  ////          m.update(rkCols(ix).toColumnName, (rkCols(ix),
  ////            hbaseFieldToRowField(cval, rkCols(ix).dataType)))
  ////          m
  ////      }
  ////      TreeMap(rmap.toArray: _*)(Ordering.by { cn: ColumnName => rmap(cn)._1.ordinal})
  ////        .asInstanceOf[SortedMap[ColumnName, (Column, Any)]]
  ////    }
  //
  //    def show(bytes: Array[Byte]) = {
  //      val len = bytes.length
  //      //      val out = s"Version=${bytes(0).toInt} NumDims=${bytes(len - 1)} "
  //    }
  //
  //  }

  def buildRow(projections: Seq[(Attribute, Int)],
               result: Result,
               buffer: ListBuffer[HBaseRawType],
               aBuffer: ArrayBuffer[Byte],
               row: MutableRow): Row = {
    assert(projections.size == row.length, "Projection size and row size mismatched")
    // TODO: replaced with the new Key method
    val rowKeys = HBaseKVHelper.decodingRawKeyColumns(buffer, aBuffer, result.getRow, keyColumns)
    projections.foreach { p =>
      columnMap.get(p._1.name).get match {
        case column: NonKeyColumn =>
          val colValue = result.getValue(column.familyRaw, column.qualifierRaw)
          DataTypeUtils.setRowColumnFromHBaseRawType(row, p._2, colValue,
            column.dataType)
        case ki =>
          val keyIndex = ki.asInstanceOf[Int]
          val rowKey = rowKeys(keyIndex)
          DataTypeUtils.setRowColumnFromHBaseRawType(row, p._2, rowKey,
            keyColumns(keyIndex).dataType)
      }
    }
    row
  }
}
