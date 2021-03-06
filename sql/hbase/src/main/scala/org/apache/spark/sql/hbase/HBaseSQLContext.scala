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

import org.apache.spark.SparkContext
import org.apache.spark.sql.catalyst.SparkSQLParser
import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.analysis.OverrideCatalog

class HBaseSQLContext(sc: SparkContext,
                      val optConfiguration: Option[Configuration] = None)
  extends SQLContext(sc) {

  @transient
  override protected[sql] val sqlParser = {
    val fallback = new HBaseSQLParser
    new SparkSQLParser(fallback(_))
  }

  @transient
  override protected[sql] lazy val catalog: HBaseCatalog =
    new HBaseCatalog(this) with OverrideCatalog

  extraStrategies = Seq((new SparkPlanner with HBaseStrategies).HBaseDataSource)
}
