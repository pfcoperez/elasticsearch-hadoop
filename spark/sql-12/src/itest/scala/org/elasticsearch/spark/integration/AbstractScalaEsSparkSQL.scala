/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.spark.integration;

import java.{ util => ju, lang => jl }
import java.sql.Timestamp
import java.math.BigInteger
import java.util.concurrent.TimeUnit
import scala.collection.JavaConversions.propertiesAsScalaMap
import org.apache.spark.SparkException
import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.sql.IntegerType
import org.apache.spark.sql.Row
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.StringType
import org.apache.spark.sql.StructField
import org.apache.spark.sql.StructType
import org.apache.spark.sql.TimestampType
import org.apache.spark.sql.DecimalType
import org.elasticsearch.hadoop.mr.RestUtils
import org.elasticsearch.hadoop.util.TestSettings
import org.elasticsearch.hadoop.util.TestUtils
import org.elasticsearch.spark._
import org.elasticsearch.spark.rdd.EsSpark
import org.elasticsearch.spark.sql._
import org.elasticsearch.spark.sql.sqlContextFunctions
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.is
import org.hamcrest.Matchers.not
import org.junit.AfterClass
import org.junit.Assert._
import org.junit.Assume._
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters
import org.elasticsearch.hadoop.cfg.ConfigurationOptions._
import org.junit.Test
import javax.xml.bind.DatatypeConverter
import org.apache.spark.sql.catalyst.expressions.GenericRow
import java.util.Arrays
import java.nio.file.Path
import java.io.File
import org.apache.commons.io.FileUtils
import org.apache.hadoop.mapred.JobContext
import org.elasticsearch.hadoop.util.StringUtils
import scala.collection.JavaConverters.asScalaBufferConverter
import com.esotericsoftware.kryo.io.{ Output => KryoOutput }
import com.esotericsoftware.kryo.io.{ Input => KryoInput }
import org.elasticsearch.hadoop.EsHadoopIllegalArgumentException
import org.elasticsearch.hadoop.serialization.EsHadoopSerializationException


case class KeyValue(key: Int, value: String)

object AbstractScalaEsScalaSparkSQL {
    @transient val conf = new SparkConf().setAll(TestSettings.TESTING_PROPS).set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    .setMaster("local").setAppName("estest").set("spark.executor.extraJavaOptions", "-XX:MaxPermSize=256m")
    .setJars(SparkUtils.ES_SPARK_TESTING_JAR)
  //.setMaster("local")

  @transient var cfg: SparkConf = null
  @transient var sc: SparkContext = null
  @transient var sqc: SQLContext = null

  @BeforeClass
  def setup() {
    conf.setAll(TestSettings.TESTING_PROPS);
    sc = new SparkContext(conf)
    sqc = new SQLContext(sc)
  }

  @AfterClass
  def cleanup() {
    if (sc != null) {
      sc.stop
      // give jetty time to clean its act up
      Thread.sleep(TimeUnit.SECONDS.toMillis(3))
    }
  }

  @Parameters
  def testParams(): ju.Collection[Array[jl.Object]] = {
    val list = new ju.ArrayList[Array[jl.Object]]()
    list.add(Array("default_", jl.Boolean.FALSE))
    list.add(Array("with_meta_", jl.Boolean.TRUE))
    list
  }
}

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@RunWith(classOf[Parameterized])
class AbstractScalaEsScalaSparkSQL(prefix: String, readMetadata: jl.Boolean) extends Serializable {

  val sc = AbstractScalaEsScalaSparkSQL.sc
  val sqc = AbstractScalaEsScalaSparkSQL.sqc
  val cfg = Map(ES_READ_METADATA -> readMetadata.toString())


  //@Test
  def test1KryoScalaEsRow() {
    val kryo = SparkUtils.sparkSerializer(sc.getConf)
    val row = new ScalaEsRow(StringUtils.tokenize("foo,bar,tar").asScala)

    val storage = Array.ofDim[Byte](512)
    val output = new KryoOutput(storage)
    val input = new KryoInput(storage)

    kryo.writeClassAndObject(output, row)
    val serialized = kryo.readClassAndObject(input).asInstanceOf[ScalaEsRow]
    println(serialized.rowOrder)
  }

  @Test
  def testBasicRead() {
    val schemaRDD = artistsAsSchemaRDD
    assertTrue(schemaRDD.count > 300)
    schemaRDD.registerTempTable("datfile")
    println(schemaRDD.schemaString)
    //schemaRDD.take(5).foreach(println)
    val results = sqc.sql("SELECT name FROM datfile WHERE id >=1 AND id <=10")
    //results.take(5).foreach(println)
  }

  @Test
  def testEsSchemaRDD1Write() {
    val schemaRDD = artistsAsSchemaRDD

    val target = wrapIndex("sparksql-test/scala-basic-write")
    schemaRDD.saveToEs(target, cfg)
    assertTrue(RestUtils.exists(target))
    assertThat(RestUtils.get(target + "/_search?"), containsString("345"))
  }

  @Test
  def testEsSchemaRDD1WriteWithMapping() {
    val schemaRDD = artistsAsSchemaRDD

    val target = wrapIndex("sparksql-test/scala-basic-write-id-mapping")
    val newCfg = collection.mutable.Map(cfg.toSeq: _*) += (ES_MAPPING_ID -> "id", ES_MAPPING_EXCLUDE -> "url")
    schemaRDD.saveToEs(target, newCfg)
    assertTrue(RestUtils.exists(target))
    assertThat(RestUtils.get(target + "/_search?"), containsString("345"))
    assertThat(RestUtils.exists(target + "/1"), is(true))
    assertThat(RestUtils.get(target + "/_search?"), not(containsString("url")))
  }

  //@Test
  def testEsSchemaRDDWarning() {
    val schemaRDD = artistsAsSchemaRDD

    val target = "sparksql-test/scala-basic-write-id-mapping"
    EsSpark.saveToEs(schemaRDD, target, cfg)
  }

  @Test
  def testEsSchemaRDD2Read() {
    val target = wrapIndex("sparksql-test/scala-basic-write")

    val schemaRDD = sqc.esRDD(target)
    assertTrue(schemaRDD.count > 300)
    val schema = schemaRDD.schemaString
    assertTrue(schema.contains("id: long"))
    assertTrue(schema.contains("name: string"))
    assertTrue(schema.contains("pictures: string"))
    assertTrue(schema.contains("time: long"))
    assertTrue(schema.contains("url: string"))

    //schemaRDD.take(5).foreach(println)

    val tempTable = wrapIndex("basicRead")
    schemaRDD.registerTempTable(tempTable)
    val nameRDD = sqc.sql("SELECT name FROM " + tempTable + " WHERE id >= 1 AND id <=10")
    nameRDD.take(7).foreach(println)
    assertEquals(10, nameRDD.count)
  }

  @Test
  def testEsSchemaRDD3WriteWithRichMapping() {
    val input = TestUtils.sampleArtistsDat()
    val data = sc.textFile(input)

    val schema = StructType(Seq(StructField("id", IntegerType, false),
      StructField("name", StringType, false),
      StructField("url", StringType, true),
      StructField("pictures", StringType, true),
      StructField("time", TimestampType, true),
      StructField("nested",
        StructType(Seq(StructField("id", IntegerType, false),
          StructField("name", StringType, false),
          StructField("url", StringType, true),
          StructField("pictures", StringType, true),
          StructField("time", TimestampType, true))), true)))

    val rowRDD = data.map(_.split("\t")).map(r => Row(r(0).toInt, r(1), r(2), r(3), new Timestamp(DatatypeConverter.parseDateTime(r(4)).getTimeInMillis()),
      Row(r(0).toInt, r(1), r(2), r(3), new Timestamp(DatatypeConverter.parseDateTime(r(4)).getTimeInMillis()))))
    val schemaRDD = sqc.applySchema(rowRDD, schema)

    val target = "sparksql-test/scala-basic-write-rich-mapping-id-mapping"
    schemaRDD.saveToEs(target, Map(ES_MAPPING_ID -> "id"))
    assertTrue(RestUtils.exists(target))
    assertThat(RestUtils.get(target + "/_search?"), containsString("345"))
    assertThat(RestUtils.exists(target + "/1"), is(true))
  }

  @Test(expected = classOf[SparkException])
  def testEsDataFrame3WriteDecimalType() {
    val schema = StructType(Seq(StructField("decimal", DecimalType(), false)))

    val rowRDD = sc.makeRDD(Seq(Row(new BigInteger("10"))))
    val dataFrame = sqc.applySchema(rowRDD, schema)

    val target = wrapIndex("sparksql-test/decimal-exception")
    dataFrame.saveToEs(target)
  }

  @Test
  def testEsSchemaRDD4ReadRichMapping() {
    val target = "sparksql-test/scala-basic-write-rich-mapping-id-mapping"

    val schemaRDD = sqc.esRDD(target, cfg)

    assertTrue(schemaRDD.count > 300)
    println(schemaRDD.schemaString)
  }

  private def artistsAsSchemaRDD = {
    val input = TestUtils.sampleArtistsDat()
    val data = sc.textFile(input)

    val schema = StructType(Seq(StructField("id", IntegerType, false),
      StructField("name", StringType, false),
      StructField("url", StringType, true),
      StructField("pictures", StringType, true),
      StructField("time", TimestampType, true)))

    val rowRDD = data.map(_.split("\t")).map(r => Row(r(0).toInt, r(1), r(2), r(3), new Timestamp(DatatypeConverter.parseDateTime(r(4)).getTimeInMillis())))
    val schemaRDD = sqc.applySchema(rowRDD, schema)
    schemaRDD
  }

  private def artistsAsBasicSchemaRDD = {
    val input = TestUtils.sampleArtistsDat()
    val data = sc.textFile(input)

    val schema = StructType(Seq(StructField("id", IntegerType, false),
      StructField("name", StringType, false),
      StructField("url", StringType, true),
      StructField("pictures", StringType, true)))

    val rowRDD = data.map(_.split("\t")).map(r => Row(r(0).toInt, r(1), r(2), r(3)))
    val schemaRDD = sqc.applySchema(rowRDD, schema)
    schemaRDD
  }

  @Test
  def testEsSchemaRDD50ReadAsDataSource() {
    val target = wrapIndex("sparksql-test/scala-basic-write")
    var options = "resource \"" + target + "\""
    if (readMetadata) {
      options = options + " ,read_metadata \"true\""
    }
    val table = wrapIndex("sqlbasicread1")

    val schemaRDD = sqc.sql("CREATE TEMPORARY TABLE " + table +
      " USING org.elasticsearch.spark.sql " +
      " OPTIONS (" + options + ")")

    val dsCfg = collection.mutable.Map(cfg.toSeq: _*) += ("path" -> target)

    val allRDD = sqc.sql("SELECT * FROM " + table + " WHERE id >= 1 AND id <=10")
    println(allRDD.schemaString)

    val nameRDD = sqc.sql("SELECT name FROM " + table + " WHERE id >= 1 AND id <=10")

    println(nameRDD.schemaString)
    assertEquals(10, nameRDD.count)
    nameRDD.take(7).foreach(println)
  }

  @Test
  def testEsSchemaFromDocsWithDifferentProperties() {
    val target = wrapIndex("spark-test/scala-sql-varcols")

    val trip1 = Map("reason" -> "business", "airport" -> "SFO")
    val trip2 = Map("participants" -> 5, "airport" -> "OTP")

    sc.makeRDD(Seq(trip1, trip2)).saveToEs(target, cfg)

    val table = wrapIndex("sqlvarcol")
    val schemaRDD = sqc.sql("CREATE TEMPORARY TABLE " + table +
      " USING org.elasticsearch.spark.sql " +
      " OPTIONS (resource '" + target + "')");

    val allResults = sqc.sql("SELECT * FROM " + table)
    assertEquals(2, allResults.count())
    println(allResults.schemaString)

    val filter = sqc.sql("SELECT * FROM " + table + " WHERE airport = 'OTP'")
    assertEquals(1, filter.count())

    val nullColumns = sqc.sql("SELECT reason, airport FROM " + table + " ORDER BY airport")
    val rows = nullColumns.take(2)
    assertEquals("[null,OTP]", rows(0).toString())
    assertEquals("[business,SFO]", rows(1).toString())
  }

  @Test
  def testJsonLoadAndSavedToEs() {
    val input = sqc.jsonFile(this.getClass.getResource("/simple.json").toURI().toString())
    input.printSchema
    println(input.schema)
    input.saveToEs("spark-test/json-file")

    val basic = sqc.jsonFile(this.getClass.getResource("/basic.json").toURI().toString())
    basic.printSchema
    println(basic.schema)
    basic.saveToEs("spark-test/json-file")
  }

  @Test
  def testJsonLoadAndSavedToEsSchema() {
    assumeFalse(readMetadata)
    val input = sqc.jsonFile(this.getClass.getResource("/multi-level-doc.json").toURI().toString())
    println(input.schema.treeString)
    println(input.schema)

    val table = wrapIndex("json_file_schema")
    val target = wrapIndex("spark-test/json-file-schema")
    input.saveToEs(target, cfg)

    val sample = input.take(1)(0).toString()

    val schemaRDD = sqc.sql("CREATE TEMPORARY TABLE " + table +
      " USING org.elasticsearch.spark.sql " +
      " OPTIONS (resource '" + target + "')");

    println("Reading information from Elastic")
    val allResults = sqc.sql("SELECT * FROM " + table)

    println("JSON schema")
    println(input.schema.treeString)
    println("Elasticsearch schema")
    println(allResults.schema.treeString)
    val dfload = allResults.take(1)(0).toString()

    assertEquals(input.schema.treeString.replace(" integer ", " long "), allResults.schema.treeString)
    assertEquals(sample, dfload)
  }

  @Test
  def testKeyValueParquetFile() {
    // Parquet is compiled against Hadoop 2 so check whether that's the case or not
    assumeTrue(classOf[JobContext].isInterface())

    //val items = 128
    //val rdd = sc.parallelize(1 to items).flatMap(i => Seq.fill(items)(KeyValue(i, i.toString)))

    val outputParquet = "bin/keyvaluerdd.parquet"
    FileUtils.deleteDirectory(new File(outputParquet))

    // running into https://issues.apache.org/jira/browse/SPARK-5281
    //sqc.createSchemaRDD(rdd).saveAsParquetFile(outputParquet)
    val schemaRDD = artistsAsBasicSchemaRDD
    schemaRDD.saveAsParquetFile(outputParquet)

    sqc.parquetFile(outputParquet).registerTempTable("testparquet")
    val select = sqc.sql("SELECT * FROM testparquet")
    println(select.schema)
    select.saveToEs("test/parquet")
  }

  //@Test
  // insert not supported
  def testEsSchemaRDD51WriteAsDataSource() {
    val target = "sparksql-test/scala-basic-write"
    val schemaRDD = sqc.sql("CREATE TEMPORARY TABLE sqlbasicwrite " +
      "USING org.elasticsearch.spark.sql " +
      "OPTIONS (resource '" + target + "')");

    val insertRDD = sqc.sql("INSERT INTO sqlbasicwrite SELECT 123456789, 'test-sql', 'http://test-sql.com', '', 12345")

    println(insertRDD.schemaString)
    assertTrue(insertRDD.count == 1)
    insertRDD.take(7).foreach(println)
  }

  def wrapIndex(index: String) = {
    prefix + index
  }
}