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

import org.apache.log4j.Logger
import org.apache.spark.Partition

/**
 * HBasePartition
 * Created by sboesch on 9/9/14.
 */
case class HBasePartition(idx : Int, bounds : Product2[Array[Byte],Array[Byte]],
                          server: String)  extends Partition {

  /**
   * Get the split's index within its parent RDD
   */
  override def index: Int = idx

}