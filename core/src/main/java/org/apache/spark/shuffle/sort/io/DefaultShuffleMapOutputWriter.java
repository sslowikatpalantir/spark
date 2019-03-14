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

package org.apache.spark.shuffle.sort.io;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.spark.SparkConf;
import org.apache.spark.api.shuffle.ShuffleMapOutputWriter;
import org.apache.spark.api.shuffle.ShufflePartitionWriter;
import org.apache.spark.shuffle.ShuffleWriteMetricsReporter;
import org.apache.spark.internal.config.package$;
import org.apache.spark.shuffle.IndexShuffleBlockResolver;
import org.apache.spark.storage.TimeTrackingOutputStream;
import org.apache.spark.util.Utils;

public class DefaultShuffleMapOutputWriter implements ShuffleMapOutputWriter {

  private static final Logger log =
    LoggerFactory.getLogger(DefaultShuffleMapOutputWriter.class);

  private final int shuffleId;
  private final int mapId;
  private final int numPartitions;
  private final ShuffleWriteMetricsReporter metrics;
  private final IndexShuffleBlockResolver blockResolver;
  private final long[] partitionLengths;
  private ShufflePartitionWriter[] partitionWriters;
  private final int bufferSize;
  private int currPartitionId = 0;

  private final File outputFile;
  private final File outputTempFile;
  private FileOutputStream outputFileStream;
  private TimeTrackingOutputStream ts;
  private BufferedOutputStream outputBufferedFileStream;

  public DefaultShuffleMapOutputWriter(
      int shuffleId,
      int mapId,
      int numPartitions,
      ShuffleWriteMetricsReporter metrics,
      IndexShuffleBlockResolver blockResolver,
      SparkConf sparkConf) {
    this.shuffleId = shuffleId;
    this.mapId = mapId;
    this.numPartitions = numPartitions;
    this.metrics = metrics;
    this.blockResolver = blockResolver;
    this.bufferSize =
      (int) (long) sparkConf.get(
        package$.MODULE$.SHUFFLE_UNSAFE_FILE_OUTPUT_BUFFER_SIZE()) * 1024;
    this.partitionLengths = new long[numPartitions];
    this.partitionWriters = new ShufflePartitionWriter[numPartitions];
    this.outputFile = blockResolver.getDataFile(shuffleId, mapId);
    this.outputTempFile = Utils.tempFileWith(outputFile);
  }

  @Override
  public ShufflePartitionWriter getNextPartitionWriter() throws IOException {
    initStream();
    ShufflePartitionWriter shufflePartitionWriter =
      new DefaultShufflePartitionWriter(
        new DefaultShuffleBlockOutputStreamImpl());
    partitionWriters[currPartitionId++] = shufflePartitionWriter;
    return shufflePartitionWriter;
  }

  @Override
  public void commitAllPartitions() throws IOException {
    for (int pId = 0; pId < numPartitions; pId ++) {
      partitionLengths[pId] = partitionWriters[pId].getLength();
    }
    blockResolver.writeIndexFileAndCommit(shuffleId, mapId, partitionLengths, outputTempFile);
    // TODO: Maybe monitor a little more intelligably then checking files?
    if (!outputFile.getParentFile().isDirectory() && !outputFile.getParentFile().mkdirs()) {
      throw new IOException(
              String.format(
                      "Failed to create shuffle file directory at %s.",
                      outputFile.getParentFile().getAbsolutePath()));
    }
    if (!outputFile.isFile() && !outputFile.createNewFile()) {
      throw new IOException(
              String.format(
                      "Failed to create empty shuffle file at %s.", outputFile.getAbsolutePath()));
    }
  }

  @Override
  public void abort(Throwable error) throws IOException {
    cleanUp();
    if (!outputTempFile.delete()) {
      log.warn("Failed to delete temporary shuffle file at {}", outputTempFile.getAbsolutePath());
    }
    if (!outputFile.delete()) {
      log.warn("Failed to delete outputshuffle file at {}", outputTempFile.getAbsolutePath());
    }
  }

  private void cleanUp() throws IOException {
    if (outputBufferedFileStream != null) {
      outputBufferedFileStream.flush();
      outputBufferedFileStream.close();
    }

    if (outputFileStream != null) {
      outputFileStream.flush();
      outputFileStream.close();
    }
  }

  private void initStream() throws IOException {
    if (outputFileStream == null) {
      outputFileStream = new FileOutputStream(outputTempFile, true);
      ts = new TimeTrackingOutputStream(metrics, outputFileStream);
    }
    if (outputBufferedFileStream == null) {
      outputBufferedFileStream = new BufferedOutputStream(ts, bufferSize);
    }
  }

  private class DefaultShuffleBlockOutputStreamImpl extends DefaultShuffleBlockOutputStream {

    private boolean isClosed = false;
    private int count = 0;

    @Override
    public int getCount() {
      return count;
    }

    @Override
    public void write(int b) throws IOException {
      if (isClosed) {
        throw new IllegalStateException("Attempting to write to a closed block byte channel.");
      }
      outputBufferedFileStream.write(b);
      count++;
    }

    @Override
    public void close() throws IOException {
      flush();
      isClosed = true;
    }

    @Override
    public void flush() throws IOException {
      outputBufferedFileStream.flush();
    }
  }
}
