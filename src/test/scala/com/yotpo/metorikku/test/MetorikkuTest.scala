package com.yotpo.metorikku.test

import java.io.{File, FileNotFoundException}
import java.io.File
import java.nio.file.{Files, Paths}

import com.yotpo.metorikku.Metorikku
import com.yotpo.metorikku.configuration.test.{Configuration, Mock, Params}
import com.yotpo.metorikku.configuration.test.ConfigurationParser.{TesterConfig, parseConfigurationFile}
import com.yotpo.metorikku.exceptions.MetorikkuInvalidMetricFileException
import org.apache.spark.sql.SparkSession
import org.scalatest.{BeforeAndAfterAll, FunSuite}

class MetorikkuTest extends FunSuite with BeforeAndAfterAll {

  override def afterAll(): Unit = {
    org.apache.commons.io.FileUtils.deleteDirectory(new File("src/test/out"))
  }

  test("Test Metorikku should load a table and filter") {

    Metorikku.main(Array(
      "-c", "src/test/scala/com/yotpo/metorikku/test/metorikku-test-config.yaml"))

    assert(new File("src/test/out/metric_test/metric/testOutput/._SUCCESS.crc").exists)
    assert(new File("src/test/out/metric_test/metric/filteredOutput/._SUCCESS.crc").exists)

    val sparkSession = SparkSession.builder.getOrCreate()

    val testOutput = sparkSession.table("testOutput")
    val filterOutput = sparkSession.table("filteredOutput")

    testOutput.cache
    filterOutput.cache

    assert(testOutput.count === 5)
    assert(filterOutput.count === 1)
  }

  test("Test Metorikku should Fail on invalid metics") {
    val thrown = intercept[FileNotFoundException] {
      Metorikku.main(Array("-c", "src/test/scala/com/yotpo/metorikku/test/metorikku-test-config-invalid-metrics.yaml"))
    }
    assert(thrown.getMessage.startsWith("No Files to Run"))

  }

  test("Test Metorikku should Fail on invalid inputs path") {
    val thrown = intercept[Exception] {
      Metorikku.main(Array("-c", "src/test/scala/com/yotpo/metorikku/test/metorikku-test-config-invalid-inputs.yaml"))
    }
    assert(thrown.getMessage.startsWith("Path does not exist"))

  }

  test("Test Metorikku should Fail on invalid Writer") {
    assertThrows[MetorikkuInvalidMetricFileException] {
      Metorikku.main(Array("-c", "src/test/scala/com/yotpo/metorikku/test/metorikku-test-config-invalid-writer.yaml"))
    }
  }

  test("Test Metorikku should Fail on invalid query without fail non gracefully") {
    val thrown = intercept[Exception] {
      Metorikku.main(Array("-c", "src/test/scala/com/yotpo/metorikku/test/metorikku-test-config-invalid-query.yaml"))
    }
    assert(thrown.getCause.getMessage.startsWith("cannot resolve '`non_existing_column`'"))
  }


  test("Test Metorikku should not fail on invalid query when ignoreOnFailures is set to true") {
    Metorikku.main(Array(
      "-c", "src/test/scala/com/yotpo/metorikku/test/metorikku-test-config-ignore-step.yaml"))

    assert(new File("src/test/out/metric_test/metric/testOutput/._SUCCESS.crc").exists)

    val sparkSession = SparkSession.builder.getOrCreate()
    val testOutput = sparkSession.table("testOutput")

    testOutput.cache
    assert(testOutput.count === 5)
  }


  //TODO Add the following tests for metorikku tester:
  /*
  failures:
  1. define invalid keys for table
  2. duplications in expected results
  3. duplications in partial - keyed results (same values for key columns in more than 1 expected result)
  4. duplications in actual results
  5. duplications in actual and expected results
  6. different scheme for part of the expected results
  7. expected does not match actual - assert the exception's msg gives enough info for the mismatch
  8. give the expected results in unsorted order and check its output keeps the order between results

9. multiple tables
10. csv
11. success


     run all of the above with a table settings that define configured keys and settings which do not define any keys
  *
  */

  test("Test Metorikku should Fail on invalid keys configuration") {
    var tableName = ""
    var definedKeys = List[String]()
    var allKeys = List[String]()
    var undefinedCols = List[String]()
    val thrown = intercept[Exception] {
      val test = parseConfigurationFile("src/test/configurations/metorikku-tester-test-invalid-keys.json")
      val basePath = new File("src/test/configurations")
      val preview = 5
      val testConf = TesterConfig(test, basePath, preview)
      tableName = testConf.test.tests.head._1
      val optionalKeys = testConf.test.keys
      optionalKeys match {
        case Some(keys) =>
          definedKeys = keys.head._2
        case _ =>
      }
      allKeys = testConf.test.tests.mapValues(v => v(0).keys.toList).head._2
      undefinedCols = definedKeys.filter(definedKey => !allKeys.contains(definedKey)).toList
      Tester(testConf).run()
    }
    val headerExpectedMsg = ErrorMsgs.getErrorByType(ErrorMsgData(ErrorType.InvalidKeysNonExisting, tableName, undefinedCols, allKeys))
    assert(thrown.getMessage.contains(headerExpectedMsg))
  }

  test("Test Metorikku should Fail on inconsistent schema") {
    val thrown = intercept[Exception] {
      val test = parseConfigurationFile("src/test/configurations/metorikku-tester-test-invalid-results.json")
      val basePath = new File("src/test/configurations")
      val preview = 5
      val testConf = TesterConfig(test, basePath, preview)
      Tester(testConf).run()
    }
    var invalidSchema = Map[String, List[Int]]()
    val mismatchedSchemaIndexes = List[Int](1,2)
    invalidSchema += ("accountsDf" -> mismatchedSchemaIndexes)
    val expectedMsg = ErrorMsgs.getErrorByType(ErrorMsgData(ErrorType.InvalidSchemaResults, invalidSchema))
    assert(thrown.getMessage.contains(expectedMsg))
  }

  test("Test Metorikku should Fail on inconsistent schema with keys") {
    val thrown = intercept[Exception] {
      val test = parseConfigurationFile("src/test/configurations/metorikku-tester-test-invalid-results-with-keys.json")
      val basePath = new File("src/test/configurations")
      val preview = 5
      val testConf = TesterConfig(test, basePath, preview)
      Tester(testConf).run()
    }
    var invalidSchema = Map[String, List[Int]]()
    val mismatchedSchemaIndexes = List[Int](1,2)
    invalidSchema += ("accountsDf" -> mismatchedSchemaIndexes)
    val expectedMsg = ErrorMsgs.getErrorByType(ErrorMsgData(ErrorType.InvalidSchemaResults, invalidSchema))
    assert(thrown.getMessage.contains(expectedMsg))
  }

  test("Test Metorikku should Fail on duplicated expected results") {
    val thrown = intercept[Exception] {
      val test = parseConfigurationFile("src/test/configurations/metorikku-tester-test-duplications-exp.json")
      val basePath = new File("src/test/configurations")
      val preview = 5
      val testConf = TesterConfig(test, basePath, preview)
      Tester(testConf).run()
    }
    val headerExpectedMsg = ErrorMsgs.getErrorByType(ErrorMsgData(ErrorType.DuplicatedResultsHeader))
    assert(thrown.getMessage.contains(headerExpectedMsg))
    val expectedMsg = ErrorMsgs.getErrorByType(ErrorMsgData(ErrorType.DuplicatedResults, "app_key=AAAA, id=A", ResultsType.expected, List(0,3)))
    assert(thrown.getMessage.contains(expectedMsg))
  }

  test("Test Metorikku should Fail on duplicated keyed expected results") {
    val thrown = intercept[Exception] {
      val test = parseConfigurationFile("src/test/configurations/metorikku-tester-test-duplications-keys.json")
      val basePath = new File("src/test/configurations")
      val preview = 5
      val testConf = TesterConfig(test, basePath, preview)
      Tester(testConf).run()
    }
    val headerExpectedMsg = ErrorMsgs.getErrorByType(ErrorMsgData(ErrorType.DuplicatedResultsHeader))
    assert(thrown.getMessage.contains(headerExpectedMsg))
    val expectedMsg = ErrorMsgs.getErrorByType(ErrorMsgData(ErrorType.DuplicatedResults, "app_key=CCCC", ResultsType.expected, List(0,2)))
    assert(thrown.getMessage.contains(expectedMsg))
  }

  test("Test Metorikku should Fail on duplicated actual results") {
    val thrown = intercept[Exception] {
      val test = parseConfigurationFile("src/test/configurations/metorikku-tester-test-duplications-actual.json")
      val basePath = new File("src/test/configurations")
      val preview = 5
      val testConf = TesterConfig(test, basePath, preview)
      Tester(testConf).run()
    }
    val headerExpectedMsg = ErrorMsgs.getErrorByType(ErrorMsgData(ErrorType.DuplicatedResultsHeader))
    assert(thrown.getMessage.contains(headerExpectedMsg))
    val expectedMsg = ErrorMsgs.getErrorByType(ErrorMsgData(ErrorType.DuplicatedResults, "app_key=BBBB, id=B", ResultsType.actual, List(1,3)))
    assert(thrown.getMessage.contains(expectedMsg))
  }

  test("Test Metorikku should Fail on duplicated actual results with keys") {
    val thrown = intercept[Exception] {
      val test = parseConfigurationFile("src/test/configurations/metorikku-tester-test-duplications-actual.json")
      val basePath = new File("src/test/configurations")
      val preview = 5
      val testConf = TesterConfig(test, basePath, preview)
      Tester(testConf).run()
    }
    val headerExpectedMsg = ErrorMsgs.getErrorByType(ErrorMsgData(ErrorType.DuplicatedResultsHeader))
    assert(thrown.getMessage.contains(headerExpectedMsg))
    val expectedMsg = ErrorMsgs.getErrorByType(ErrorMsgData(ErrorType.DuplicatedResults, "app_key=BBBB, id=B", ResultsType.actual, List(1,3)))
    assert(thrown.getMessage.contains(expectedMsg))
  }

  test("Test Metorikku should Fail on duplicated keyed actual results") {
    val thrown = intercept[Exception] {
      val test = parseConfigurationFile("src/test/configurations/metorikku-tester-test-duplications-actual-with-keys.json")
      val basePath = new File("src/test/configurations")
      val preview = 5
      val testConf = TesterConfig(test, basePath, preview)
      Tester(testConf).run()
    }
    val headerExpectedMsg = ErrorMsgs.getErrorByType(ErrorMsgData(ErrorType.DuplicatedResultsHeader))
    assert(thrown.getMessage.contains(headerExpectedMsg))
    val expectedMsg = ErrorMsgs.getErrorByType(ErrorMsgData(ErrorType.DuplicatedResults, "app_key=BBBB", ResultsType.actual, List(1,3)))
    assert(thrown.getMessage.contains(expectedMsg))
  }

  test("Test Metorikku should Fail on duplicated actual anscad expected results") {
    val thrown = intercept[Exception] {
      val test = parseConfigurationFile("src/test/configurations/metorikku-tester-test-duplications-actual-exp.json")
      val basePath = new File("src/test/configurations")
      val preview = 5
      val testConf = TesterConfig(test, basePath, preview)
      Tester(testConf).run()
    }
    val headerExpectedMsg = ErrorMsgs.getErrorByType(ErrorMsgData(ErrorType.DuplicatedResultsHeader))
    assert(thrown.getMessage.contains(headerExpectedMsg))
    val actualResMsg = ErrorMsgs.getErrorByType(ErrorMsgData(ErrorType.DuplicatedResults, "app_key=BBBB, id=B", ResultsType.actual, List(1,3)))
    assert(thrown.getMessage.contains(actualResMsg))
    val expectedResMsg = ErrorMsgs.getErrorByType(ErrorMsgData(ErrorType.DuplicatedResults, "app_key=AAAA, id=A", ResultsType.expected, List(0,1)))
    assert(thrown.getMessage.contains(expectedResMsg))
  }

  test("Test Metorikku should Fail on mismatch in all columns") {
    var definedKeys = List[String]()
    val thrown = intercept[Exception] {
      val test = parseConfigurationFile("src/test/configurations/metorikku-tester-test-mismatch-results.json")
      val basePath = new File("src/test/configurations")
      val preview = 5
      val testConf = TesterConfig(test, basePath, preview)
      definedKeys = testConf.test.tests.head._2(0).keys.toList
      Tester(testConf).run()
    }
    println("-----------------Test ended. Thrown msg:-----------------")
        println(thrown.getMessage)
    var expectedRow = Map("app_key" -> "CCCC", "id" -> "CC")
    assertMismatchExpected(definedKeys, thrown.getMessage, expectedRow,3)
    expectedRow = Map("app_key" -> "DDDD", "id" -> "DD")
    assertMismatchExpected(definedKeys, thrown.getMessage, expectedRow,4)
    expectedRow = Map("app_key" -> "EEEE", "id" -> "EE")
    assertMismatchExpected(definedKeys, thrown.getMessage, expectedRow,5)
    expectedRow = Map("app_key" -> "FFFF", "id" -> "FF")
    assertMismatchExpected(definedKeys, thrown.getMessage, expectedRow,6)
    var actualRow = Map("app_key" -> "CCCC", "id" -> "C")
    assertMismatchActual(definedKeys, thrown.getMessage, actualRow,3)
    actualRow = Map("app_key" -> "DDDD", "id" -> "D")
    assertMismatchActual(definedKeys, thrown.getMessage, actualRow,4)
    actualRow = Map("app_key" -> "EEEE", "id" -> "E")
    assertMismatchActual(definedKeys, thrown.getMessage, actualRow,5)
    actualRow = Map("app_key" -> "FFFF", "id" -> "F")
    assertMismatchActual(definedKeys, thrown.getMessage, actualRow,6)
  }

  test("Test Metorikku should Fail on mismatch in all columns with keys") {
    var definedKeys = List[String]()
    val thrown = intercept[Exception] {
      val test = parseConfigurationFile("src/test/configurations/metorikku-tester-test-mismatch-results-with-keys.json")
      val basePath = new File("src/test/configurations")
      val preview = 5
      val testConf = TesterConfig(test, basePath, preview)

      val optionalKeys = testConf.test.keys
      optionalKeys match {
        case Some(keys) =>
          definedKeys = keys.head._2
        case _ =>
      }
      Tester(testConf).run()
    }
    var expectedRow = Map("app_key" -> "BBBB", "id" -> "B1")
    var actualRow = Map("app_key" -> "BBBB", "id" -> "B")
    assertMismatch(definedKeys, thrown.getMessage, actualRow, expectedRow, 2, 2)
    expectedRow = Map("app_key" -> "DDDD", "id" -> "D1")
    actualRow = Map("app_key" -> "DDDD", "id" -> "D")
    assertMismatch(definedKeys, thrown.getMessage, actualRow, expectedRow, 4, 4)
    }

  test("Test Metorikku should Fail on mismatch in key columns") {
    var definedKeys = List[String]()
    val thrown = intercept[Exception] {
      val test = parseConfigurationFile("src/test/configurations/metorikku-tester-test-mismatch-key-results.json")
      val basePath = new File("src/test/configurations")
      val preview = 5
      val testConf = TesterConfig(test, basePath, preview)
      val optionalKeys = testConf.test.keys
      optionalKeys match {
        case Some(keys) =>
          definedKeys = keys.head._2
        case _ =>
      }
      Tester(testConf).run()
    }
    var expectedRow = Map("app_key" -> "CCC", "id" -> "CC")
    assertMismatchExpected(definedKeys, thrown.getMessage, expectedRow,3)
    expectedRow = Map("app_key" -> "AAA", "id" -> "AA")
    assertMismatchExpected(definedKeys, thrown.getMessage, expectedRow,1)
  }

  def printErrorMsg(msg: String) = {
        println("-----------------Test ended. Thrown msg:-----------------")
        println(msg)
  }

  test("Test Metorikku should keep order of unsorted expected results with keys") {
    var definedKeys = List[String]()
    val thrown = intercept[Exception] {
      val test = parseConfigurationFile("src/test/configurations/metorikku-tester-test-unsorted-results-with-keys.json")
      val basePath = new File("src/test/configurations")
      val preview = 5
      val testConf = TesterConfig(test, basePath, preview)
      val optionalKeys = testConf.test.keys
      optionalKeys match {
        case Some(keys) =>
          definedKeys = keys.head._2
        case _ =>
      }
      Tester(testConf).run()
    }
    //printErrorMsg(thrown.getMessage)
    var actualRow: Map[String, Any] = Map("app_key" -> "FFFF", "id" -> "F")
    var expectedRow: Map[String, Any] = Map("app_key" -> "FFFF", "id" -> "FF")
    assertMismatch(definedKeys, thrown.getMessage, actualRow, expectedRow, 6, 6)
    actualRow = Map("app_key" -> "EEEE", "id" -> "E")
    expectedRow = Map("app_key" -> "EEEE", "id" -> "EE")
    assertMismatch(definedKeys, thrown.getMessage, actualRow, expectedRow, 4, 5)
    actualRow = Map("app_key" -> "DDDD", "id" -> "D")
    expectedRow = Map("app_key" -> "DDDD", "id" -> "DD")
    assertMismatch(definedKeys, thrown.getMessage, actualRow, expectedRow, 5,4)
    actualRow = Map("app_key" -> "CCCC", "id" -> "C")
    expectedRow = Map("app_key" -> "CCCC", "id" -> "CC")
    assertMismatch(definedKeys, thrown.getMessage, actualRow, expectedRow, 2,1)
  }

  test("Test Metorikku should Fail on different results count") {
    var definedKeys = List[String]()
    val thrown = intercept[Exception] {
      val test = parseConfigurationFile("src/test/configurations/metorikku-tester-test-diff-count-results.json")
      val basePath = new File("src/test/configurations")
      val preview = 5
      val testConf = TesterConfig(test, basePath, preview)
      definedKeys = testConf.test.tests.head._2(0).keys.toList
      Tester(testConf).run()
    }
    var expectedRow = Map("app_key" -> "DDDD", "id" -> "D")
    assertMismatchExpected(definedKeys, thrown.getMessage, expectedRow, 4)
    expectedRow = Map("app_key" -> "EEEE", "id" -> "E")
    assertMismatchExpected(definedKeys, thrown.getMessage, expectedRow, 5)
    expectedRow = Map("app_key" -> "FFFF", "id" -> "F")
    assertMismatchExpected(definedKeys, thrown.getMessage, expectedRow, 6)
  }

  test("Test Metorikku should Fail on multiple df errors - duplicated keyed actual results and mismatch in all columns with keys") {
    var definedKeys = List[String]()
    val thrown = intercept[Exception] {
      val test = parseConfigurationFile("src/test/configurations/metorikku-tester-test-duplications-actual-with-keys-df-mismatch-results-with-keys.json")
      val basePath = new File("src/test/configurations")
      val preview = 5
      val testConf = TesterConfig(test, basePath, preview)

      val optionalKeys = testConf.test.keys
      optionalKeys match {
        case Some(keys) =>
          definedKeys = keys.head._2
        case _ =>
      }
      Tester(testConf).run()
    }
    val headerExpectedMsg = ErrorMsgs.getErrorByType(ErrorMsgData(ErrorType.DuplicatedResultsHeader))
    assert(thrown.getMessage.contains(headerExpectedMsg))
    val expectedMsg = ErrorMsgs.getErrorByType(ErrorMsgData(ErrorType.DuplicatedResults, "app_key=BBBB", ResultsType.actual, List(1,3)))
    assert(thrown.getMessage.contains(expectedMsg))

    val expectedRow = Map("app_key" -> "CCCC", "id" -> "d")
    val actualRow = Map("app_key" -> "CCCC", "id" -> "C")
    assertMismatch(definedKeys, thrown.getMessage, actualRow, expectedRow, 2, 3)

  }

  test("Test Metorikku should pass when results match") {
    var definedKeys = List[String]()
    val thrown = intercept[Exception] {
      val test = parseConfigurationFile("src/test/configurations/metorikku-tester-test-diff-count-results.json")
      val basePath = new File("src/test/configurations")
      val preview = 5
      val testConf = TesterConfig(test, basePath, preview)
      definedKeys = testConf.test.tests.head._2(0).keys.toList
      Tester(testConf).run()
    }
    var expectedRow = Map("app_key" -> "DDDD", "id" -> "D")
    assertMismatchExpected(definedKeys, thrown.getMessage, expectedRow, 4)
    expectedRow = Map("app_key" -> "EEEE", "id" -> "E")
    assertMismatchExpected(definedKeys, thrown.getMessage, expectedRow, 5)
    expectedRow = Map("app_key" -> "FFFF", "id" -> "F")
    assertMismatchExpected(definedKeys, thrown.getMessage, expectedRow, 6)
  }

  private def assertMismatchExpected(definedKeys: List[String], thrownMsg: String, expectedRow: Map[String, Any], rowIndex: Int) = {
    assertMismatchByType(definedKeys, thrownMsg, expectedRow, ErrorType.MismatchedKeyResultsExpected, 1, 0, rowIndex)
  }

  private def assertMismatchActual(definedKeys: List[String], thrownMsg: String, actualRow: Map[String, Any], rowIndex: Int) = {
    assertMismatchByType(definedKeys, thrownMsg, actualRow, ErrorType.MismatchedKeyResultsActual, 0, 1, rowIndex)
  }

  private def assertMismatchByType(definedKeys: List[String], thrownMsg: String, row: Map[String, Any], errorType: ErrorType.Value, expectedCount: Int, actualCount: Int, rowIndex: Int) = {
    val tableKeysVal = KeyColumns.getRowKey(row, definedKeys)
    val outputKey = KeyColumns.formatOutputKey(tableKeysVal, definedKeys)
    val expectedMsg = ErrorMsgs.getErrorByType(ErrorMsgData(errorType, expectedCount, outputKey, actualCount, rowIndex))
    assert(thrownMsg.contains(expectedMsg))
  }

  private def assertMismatch(definedKeys: List[String], thrownMsg: String, actualRow: Map[String, Any], expectedRow: Map[String, Any], expectedRowIndex: Int, actualRowIndex: Int) = {

    val mismatchingCols = TestUtil.getMismatchingColumns(actualRow, expectedRow)
    val tableKeysVal = KeyColumns.getRowKey(expectedRow, definedKeys)
    val outputKey = KeyColumns.formatOutputKey(tableKeysVal, definedKeys)
    val mismatchingVals = TestUtil.getMismatchedVals(expectedRow, actualRow, mismatchingCols).toList
    val expectedMsg = ErrorMsgs.getErrorByType(ErrorMsgData(ErrorType.MismatchedResultsAllCols, outputKey, expectedRowIndex, actualRowIndex, mismatchingCols.toList, mismatchingVals))
    assert(thrownMsg.contains(expectedMsg))
  }
}