/*
 * Copyright (c) 2019-2020, NVIDIA CORPORATION.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.rapids.spark

import java.io.{File, IOException}

import ai.rapids.cudf.{NvtxColor, NvtxRange, ParquetWriterOptions}
import org.apache.hadoop.fs.{FileUtil, Path}
import org.apache.hadoop.mapreduce.{Job, OutputCommitter, TaskAttemptContext}
import org.apache.parquet.hadoop.codec.CodecConfig
import org.apache.parquet.hadoop.util.ContextUtil
import org.apache.parquet.hadoop.{ParquetOutputCommitter, ParquetOutputFormat}
import org.apache.parquet.hadoop.ParquetOutputFormat.JobSummaryLevel

import org.apache.spark.internal.Logging
import org.apache.spark.sql.execution.datasources.parquet.{ParquetOptions, ParquetWriteSupport}
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.vectorized.ColumnarBatch
import org.apache.spark.sql.{Row, SparkSession}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.internal.SQLConf.ParquetOutputTimestampType
import org.apache.spark.sql.rapids.{ColumnarWriteTaskStatsTracker, GpuWriteTaskStatsTracker}
import org.apache.spark.TaskContext

object GpuParquetFileFormat {
  def tagGpuSupport(
      meta: RapidsMeta[_, _, _],
      spark: SparkSession,
      options: Map[String, String]): Option[GpuParquetFileFormat] = {
    val sqlConf = spark.sessionState.conf
    val parquetOptions = new ParquetOptions(options, sqlConf)

    parquetOptions.compressionCodecClassName match {
      case "NONE" | "UNCOMPRESSED" | "SNAPPY" =>
      case c => meta.willNotWorkOnGpu(s"compression codec $c is not supported")
    }

    if (sqlConf.writeLegacyParquetFormat) {
      meta.willNotWorkOnGpu(s"Spark legacy format is not supported")
    }

    // TODO: Could support TIMESTAMP_MILLIS by performing cast on all timestamp input columns
    sqlConf.parquetOutputTimestampType match {
      case ParquetOutputTimestampType.TIMESTAMP_MICROS =>
      case t => meta.willNotWorkOnGpu(s"Output timestamp type $t is not supported")
    }

    if (meta.canThisBeReplaced) {
      Some(new GpuParquetFileFormat)
    } else {
      None
    }
  }
}

class GpuParquetFileFormat extends ColumnarFileFormat with Logging {
  /**
   * Prepares a write job and returns an [[ColumnarOutputWriterFactory]].  Client side job
   * preparation can be put here.  For example, user defined output committer can be configured here
   * by setting the output committer class in the conf of spark.sql.sources.outputCommitterClass.
   */
  override def prepareWrite(
      sparkSession: SparkSession,
      job: Job,
      options: Map[String, String],
      dataSchema: StructType): ColumnarOutputWriterFactory = {
    val parquetOptions = new ParquetOptions(options, sparkSession.sessionState.conf)

    val conf = ContextUtil.getConfiguration(job)

    val committerClass =
      conf.getClass(
        SQLConf.PARQUET_OUTPUT_COMMITTER_CLASS.key,
        classOf[ParquetOutputCommitter],
        classOf[OutputCommitter])

    if (conf.get(SQLConf.PARQUET_OUTPUT_COMMITTER_CLASS.key) == null) {
      logInfo("Using default output committer for Parquet: " +
          classOf[ParquetOutputCommitter].getCanonicalName)
    } else {
      logInfo("Using user defined output committer for Parquet: " + committerClass.getCanonicalName)
    }

    conf.setClass(
      SQLConf.OUTPUT_COMMITTER_CLASS.key,
      committerClass,
      classOf[OutputCommitter])

    // We're not really using `ParquetOutputFormat[Row]` for writing data here.  The reason why
    // we set it here is to setup the output committer class to `ParquetOutputCommitter`, which is
    // bundled with `ParquetOutputFormat[Row]`.
    job.setOutputFormatClass(classOf[ParquetOutputFormat[Row]])

    ParquetOutputFormat.setWriteSupportClass(job, classOf[ParquetWriteSupport])

    // This metadata is useful for keeping UDTs like Vector/Matrix.
    ParquetWriteSupport.setSchema(dataSchema, conf)

    if (sparkSession.sessionState.conf.writeLegacyParquetFormat) {
      throw new UnsupportedOperationException("Spark legacy output format not supported")
    }
    // Sets flags for `ParquetWriteSupport`, which converts Catalyst schema to Parquet
    // schema and writes actual rows to Parquet files.
    conf.set(
      SQLConf.PARQUET_WRITE_LEGACY_FORMAT.key,
      sparkSession.sessionState.conf.writeLegacyParquetFormat.toString)

    val outputTimestampType = sparkSession.sessionState.conf.parquetOutputTimestampType
    if (outputTimestampType != ParquetOutputTimestampType.TIMESTAMP_MICROS) {
      throw new UnsupportedOperationException(s"Unsupported output timestamp type: $outputTimestampType")
    }
    conf.set(
      SQLConf.PARQUET_OUTPUT_TIMESTAMP_TYPE.key,
      sparkSession.sessionState.conf.parquetOutputTimestampType.toString)

    // Sets compression scheme
    conf.set(ParquetOutputFormat.COMPRESSION, parquetOptions.compressionCodecClassName)

    // SPARK-15719: Disables writing Parquet summary files by default.
    if (conf.get(ParquetOutputFormat.JOB_SUMMARY_LEVEL) == null
        && conf.get(ParquetOutputFormat.ENABLE_JOB_SUMMARY) == null) {
      conf.setEnum(ParquetOutputFormat.JOB_SUMMARY_LEVEL, JobSummaryLevel.NONE)
    }

    if (ParquetOutputFormat.getJobSummaryLevel(conf) != JobSummaryLevel.NONE
        && !classOf[ParquetOutputCommitter].isAssignableFrom(committerClass)) {
      // output summary is requested, but the class is not a Parquet Committer
      logWarning(s"Committer $committerClass is not a ParquetOutputCommitter and cannot" +
          s" create job summaries. " +
          s"Set Parquet option ${ParquetOutputFormat.JOB_SUMMARY_LEVEL} to NONE.")
    }

    new ColumnarOutputWriterFactory {
        override def newInstance(
          path: String,
          dataSchema: StructType,
          context: TaskAttemptContext): ColumnarOutputWriter = {
        new GpuParquetWriter(path, dataSchema, context)
      }

      override def getFileExtension(context: TaskAttemptContext): String = {
        CodecConfig.from(context).getCodec.getExtension + ".parquet"
      }
    }
  }
}

class GpuParquetWriter(
    path: String,
    dataSchema: StructType,
    context: TaskAttemptContext) extends ColumnarOutputWriter {
  /**
   * Closes the [[ColumnarOutputWriter]]. Invoked on the executor side after all columnar batches
   * are persisted, before the task output is committed.
   */
  override def close(): Unit = {}

  // write a batch and return the time spent on the GPU
  override def writeBatch(batch: ColumnarBatch): Long = {
    var needToCloseBatch = true
    var tempFile: Option[File] = None
    try {
      val conf = context.getConfiguration
      val writeContext = new ParquetWriteSupport().init(conf)
      val options = ParquetWriterOptions.builder()
          .withColumnNames(dataSchema.map(_.name):_*)
          .withMetadata(writeContext.getExtraMetaData)
          .build()
      tempFile = Some(File.createTempFile("gpu", ".parquet"))
      tempFile.get.getParentFile.mkdirs()
      val startTimestamp = System.nanoTime
      val nvtxRange = new NvtxRange("GPU Parquet write", NvtxColor.BLUE)
      try {
        val table = GpuColumnVector.from(batch)
        try {
          table.writeParquet(options, tempFile.get)
        } finally {
          table.close()
        }
      } finally {
        nvtxRange.close()
      }

      // Batch is no longer needed, write process from here does not use GPU.
      batch.close()
      needToCloseBatch = false
      GpuSemaphore.releaseIfNecessary(TaskContext.get)
      val gpuTime = System.nanoTime - startTimestamp

      val hadoopPath = new Path(path)
      if (!FileUtil.copy(tempFile.get, hadoopPath.getFileSystem(conf), hadoopPath, false, conf)) {
        throw new IOException(s"Failed to copy data to $hadoopPath")
      }

      gpuTime
    } finally {
      if (needToCloseBatch) {
        batch.close()
      }
      tempFile.foreach(_.delete())
    }
  }
}
