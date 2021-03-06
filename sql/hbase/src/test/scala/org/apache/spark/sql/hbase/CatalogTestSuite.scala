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

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.hbase.client.HBaseAdmin
import org.apache.hadoop.hbase.{HBaseConfiguration, HColumnDescriptor, HTableDescriptor, TableName}
import org.apache.spark._
import org.apache.spark.sql.catalyst.types._
import org.apache.spark.sql.catalyst.expressions.GenericRow
import org.scalatest.{BeforeAndAfterAll, FunSuite}

//@Ignore
class CatalogTestSuite extends FunSuite with BeforeAndAfterAll with Logging {
  var sparkConf: SparkConf = _
  var sparkContext: SparkContext = _
  var hbaseContext: HBaseSQLContext = _
  var configuration: Configuration = _
  var catalog: HBaseCatalog = _

  override def beforeAll() = {
    sparkConf = new SparkConf().setAppName("Catalog Test").setMaster("local[4]")
    sparkContext = new SparkContext(sparkConf)
    hbaseContext = new HBaseSQLContext(sparkContext)
    catalog = new HBaseCatalog(hbaseContext)
    configuration = HBaseConfiguration.create()
  }

  def compare(a: Array[Byte], b: Array[Byte]): Int = {
    val length = a.length
    var result: Int = 0
    for (i <- 0 to length - 1) {
      val diff: Int = b(i) - a(i)
      if (diff != 0) {
        result = diff
      }
    }
    result
  }

  test("Bytes Utility") {
    assert(BytesUtils.toBoolean(BytesUtils.create(BooleanType)
      .toBytes(input = true)) === true)
    assert(BytesUtils.toBoolean(BytesUtils.create(BooleanType)
      .toBytes(input = false)) === false)

    assert(BytesUtils.toDouble(BytesUtils.create(DoubleType).toBytes(12.34d))
      === 12.34d)
    assert(BytesUtils.toDouble(BytesUtils.create(DoubleType).toBytes(-12.34d))
      === -12.34d)

    assert(BytesUtils.toFloat(BytesUtils.create(FloatType).toBytes(12.34f))
      === 12.34f)
    assert(BytesUtils.toFloat(BytesUtils.create(FloatType).toBytes(-12.34f))
      === -12.34f)

    assert(BytesUtils.toInt(BytesUtils.create(IntegerType).toBytes(12))
      === 12)
    assert(BytesUtils.toInt(BytesUtils.create(IntegerType).toBytes(-12))
      === -12)

    assert(BytesUtils.toLong(BytesUtils.create(LongType).toBytes(1234l))
      === 1234l)
    assert(BytesUtils.toLong(BytesUtils.create(LongType).toBytes(-1234l))
      === -1234l)

    assert(BytesUtils.toShort(BytesUtils.create(ShortType)
      .toBytes(12.asInstanceOf[Short])) === 12)
    assert(BytesUtils.toShort(BytesUtils.create(ShortType)
      .toBytes(-12.asInstanceOf[Short])) === -12)

    assert(BytesUtils.toString(BytesUtils.create(StringType).toBytes("abc"))
      === "abc")

    assert(BytesUtils.toByte(BytesUtils.create(ByteType)
      .toBytes(5.asInstanceOf[Byte])) === 5)
    assert(BytesUtils.toByte(BytesUtils.create(ByteType)
      .toBytes(-5.asInstanceOf[Byte])) === -5)
  }

  test("Create Table") {
    // prepare the test data
    val namespace = "testNamespace"
    val tableName = "testTable"
    val hbaseTableName = "hbaseTable"
    val family1 = "family1"
    val family2 = "family2"

    if (!catalog.checkHBaseTableExists(hbaseTableName)) {
      val admin = new HBaseAdmin(configuration)
      val desc = new HTableDescriptor(TableName.valueOf(hbaseTableName))
      desc.addFamily(new HColumnDescriptor(family1))
      desc.addFamily(new HColumnDescriptor(family2))
      admin.createTable(desc)
    }

    var allColumns = List[AbstractColumn]()
    allColumns = allColumns :+ KeyColumn("column2", IntegerType, 1)
    allColumns = allColumns :+ KeyColumn("column1", StringType, 0)
    allColumns = allColumns :+ NonKeyColumn("column4", FloatType, family2, "qualifier2")
    allColumns = allColumns :+ NonKeyColumn("column3", BooleanType, family1, "qualifier1")

    val splitKeys: Array[Array[Byte]] = Array(
        new GenericRow(Array(1024.0, "Upen", 128: Short)),
        new GenericRow(Array(1024.0, "Upen", 256: Short)),
        new GenericRow(Array(4096.0, "SF", 512: Short))
      ).map(HBaseKVHelper.makeRowKey(_, Seq(DoubleType, StringType, ShortType)))

    catalog.createTable(tableName, namespace, hbaseTableName, allColumns, splitKeys)
  }

  test("Get Table") {
    // prepare the test data
    val hbaseNamespace = "testNamespace"
    val tableName = "testTable"
    val hbaseTableName = "hbaseTable"

    val oresult = catalog.getTable(tableName)
    assert(oresult.isDefined)
    val result = oresult.get
    assert(result.tableName === tableName)
    assert(result.hbaseNamespace === hbaseNamespace)
    assert(result.hbaseTableName === hbaseTableName)
    assert(result.keyColumns.size === 2)
    assert(result.nonKeyColumns.size === 2)
    assert(result.allColumns.size === 4)

    // check the data type
    assert(result.keyColumns(0).dataType === StringType)
    assert(result.keyColumns(1).dataType === IntegerType)
    assert(result.nonKeyColumns(0).dataType === FloatType)
    assert(result.nonKeyColumns(1).dataType === BooleanType)

    val relation = catalog.lookupRelation(None, tableName)
    val hbRelation = relation.asInstanceOf[HBaseRelation]
    assert(hbRelation.nonKeyColumns.map(_.family) == List("family2", "family1"))
    val keyColumns = Seq(KeyColumn("column1", StringType, 0), KeyColumn("column2", IntegerType, 1))
    assert(hbRelation.keyColumns.equals(keyColumns))
    assert(relation.childrenResolved)
  }

  test("Alter Table") {
    val tableName = "testTable"

    val family1 = "family1"
    val column = NonKeyColumn("column5", BooleanType, family1, "qualifier3")

    catalog.alterTableAddNonKey(tableName, column)

    var result = catalog.getTable(tableName)
    var table = result.get
    assert(table.allColumns.size === 5)

    catalog.alterTableDropNonKey(tableName, column.sqlName)
    result = catalog.getTable(tableName)
    table = result.get
    assert(table.allColumns.size === 4)
  }

  test("Delete Table") {
    // prepare the test data
    val tableName = "testTable"

    catalog.deleteTable(tableName)
  }

  test("Check Logical Table Exist") {
    val tableName = "non-exist"

    assert(catalog.checkLogicalTableExist(tableName) === false)
  }
}
