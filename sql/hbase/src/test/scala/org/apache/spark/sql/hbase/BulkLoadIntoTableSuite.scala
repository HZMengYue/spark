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

import org.apache.hadoop.fs.{Path, FileSystem}
import org.apache.hadoop.mapreduce.Job
import org.apache.spark.sql.catalyst.analysis.UnresolvedRelation
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.scalatest.{BeforeAndAfterAll, FunSuite}
import org.apache.spark.{SparkContext, Logging}
import org.apache.spark.sql.catalyst.types.IntegerType
import org.apache.spark.sql.hbase.execution.BulkLoadIntoTableCommand
import org.apache.hadoop.hbase.util.Bytes

class BulkLoadIntoTableSuite extends FunSuite with BeforeAndAfterAll with Logging{

  val sc = new SparkContext("local", "test")
  val hbc = new HBaseSQLContext(sc)

  // Test if we can parse 'LOAD DATA LOCAL INPATH './usr/file.csv' INTO TABLE tb'
  test("bulkload parser test, local file") {

    val parser = new HBaseSQLParser()
    val sql = raw"LOAD DATA LOCAL INPATH './usr/file.csv' INTO TABLE tb"
    //val sql = "select"

    val plan: LogicalPlan = parser(sql)
    assert(plan != null)
    assert(plan.isInstanceOf[BulkLoadIntoTableCommand])

    val l = plan.asInstanceOf[BulkLoadIntoTableCommand]
    assert(l.path.equals(raw"./usr/file.csv"))
    assert(l.isLocal)
    assert(l.tableName.equals("tb"))
  }

  // Test if we can parse 'LOAD DATA INPATH '/usr/hdfsfile.csv' INTO TABLE tb'
  test("bulkload parser test, load hdfs file") {

    val parser = new HBaseSQLParser()
    val sql = raw"LOAD DATA INPATH '/usr/hdfsfile.csv' INTO TABLE tb"
    //val sql = "select"

    val plan: LogicalPlan = parser(sql)
    assert(plan != null)
    assert(plan.isInstanceOf[BulkLoadIntoTableCommand])

    val l = plan.asInstanceOf[BulkLoadIntoTableCommand]
    assert(l.path.equals(raw"/usr/hdfsfile.csv"))
    assert(!l.isLocal)
    assert(l.tableName.equals("tb"))
  }

  test("bulkload parser test, using delimiter") {

    val parser = new HBaseSQLParser()
    val sql = raw"LOAD DATA INPATH '/usr/hdfsfile.csv' INTO TABLE tb FIELDS TERMINATED BY '|' "

    val plan: LogicalPlan = parser(sql)
    assert(plan != null)
    assert(plan.isInstanceOf[BulkLoadIntoTableCommand])

    val l = plan.asInstanceOf[BulkLoadIntoTableCommand]
    assert(l.path.equals(raw"/usr/hdfsfile.csv"))
    assert(!l.isLocal)
    assert(l.tableName.equals("tb"))
    assert(l.delimiter.get.equals("|"))
  }

//  ignore("write data to HFile") {
//    val colums = Seq(new KeyColumn("k1", IntegerType, 0), new NonKeyColumn("v1", IntegerType, "cf1", "c1"))
//    val hbaseRelation = HBaseRelation("testtablename", "hbasenamespace", "hbasetablename", colums)(hbc)
//    val bulkLoad = BulkLoadIntoTableCommand("./sql/hbase/src/test/resources/test.csv", hbaseRelation, true, Option(","))
//    val splitKeys = (1 to 40).filter(_ % 5 == 0).filter(_ != 40).map { r =>
//      new ImmutableBytesWritableWrapper(Bytes.toBytes(r))
//    }
//    val conf = hbc.sparkContext.hadoopConfiguration
//
//    val job = Job.getInstance(conf)
//
//    val hadoopReader = {
//      val fs = FileSystem.getLocal(conf)
//      val pathString = fs.pathToFile(new Path(bulkLoad.path)).getCanonicalPath
//      new HadoopReader(hbc.sparkContext, pathString, bulkLoad.delimiter)(hbaseRelation)
//    }
//    val tmpPath = Util.getTempFilePath(conf, hbaseRelation.tableName)
//    bulkLoad.makeBulkLoadRDD(splitKeys.toArray, hadoopReader, job, tmpPath)
//  }

  test("load data into hbase") { // this need to local test with hbase, so here to ignore this

    val drop = "drop table testblk"
    val executeSql0 = hbc.executeSql(drop)
    try {
      executeSql0.toRdd.collect().foreach(println)
    } catch {
      case e: IllegalStateException =>
        // do not throw exception here
      println(e.getMessage)
    }

    // create sql table map with hbase table and run simple sql
    val sql1 =
      s"""CREATE TABLE testblk(col1 STRING, col2 STRING, col3 STRING, PRIMARY KEY(col1))
          MAPPED BY (wf, COLS=[col2=cf1.a, col3=cf1.b])"""
        .stripMargin

    val sql2 =
      s"""select * from testblk limit 5"""
        .stripMargin

    val executeSql1 = hbc.executeSql(sql1)
    executeSql1.toRdd.collect().foreach(println)

    val executeSql2 = hbc.executeSql(sql2)
    executeSql2.toRdd.collect().foreach(println)

    // then load data into table
    val loadSql = "LOAD DATA LOCAL INPATH './sql/hbase/src/test/resources/loadData.csv' INTO TABLE testblk"

    val executeSql3 = hbc.executeSql(loadSql)
    executeSql3.toRdd.collect().foreach(println)
    hbc.sql("select * from testblk").collect().foreach(println)
  }

  override def afterAll() {
    sc.stop()
  }

}
