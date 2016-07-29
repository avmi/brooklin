package com.linkedin.datastream.connectors;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.Metric;

import com.linkedin.data.template.StringMap;
import com.linkedin.datastream.common.Datastream;
import com.linkedin.datastream.common.DatastreamEvent;
import com.linkedin.datastream.common.DatastreamRuntimeException;
import com.linkedin.datastream.common.VerifiableProperties;
import com.linkedin.datastream.server.DatastreamProducerRecord;
import com.linkedin.datastream.server.DatastreamProducerRecordBuilder;
import com.linkedin.datastream.server.DatastreamTask;
import com.linkedin.datastream.server.api.connector.Connector;
import com.linkedin.datastream.server.api.connector.DatastreamValidationException;
import com.linkedin.datastream.testutil.common.RandomValueGenerator;


/**
 * TestConnector that can be used to generate test events at regular intervals.
 */
public class TestEventProducingConnector implements Connector {

  private static final Logger LOG = LoggerFactory.getLogger(TestEventProducingConnector.class);

  public static final String CFG_MESSAGE_SIZE_BYTES = "messageSize";
  private static final int DEFAULT_MESSAGE_SIZE_BYTES = 100;

  public static final String CFG_SLEEP_BETWEEN_SEND_MS = "sleepBetweenSendMs";
  private static final long DEFAULT_SLEEP_BETWEEN_SEND_MS = 1000;

  public static final String CFG_NUM_PRODUCER_THREADS = "numProducerThreads";
  private static final int DEFAULT_NUM_PRODUCER_THREADS = 10;
  private final ExecutorService _executor;
  private final String _hostName;
  private final RandomValueGenerator _randomValueGenerator;

  private int _messageSize;
  private long _sleepBetweenSendMs;
  private Map<DatastreamTask, Future<?>> _tasksAssigned = new HashMap<>();

  public TestEventProducingConnector(Properties props) {
    VerifiableProperties config = new VerifiableProperties(props);
    _messageSize = config.getInt(CFG_MESSAGE_SIZE_BYTES, DEFAULT_MESSAGE_SIZE_BYTES);
    _sleepBetweenSendMs = config.getLong(CFG_SLEEP_BETWEEN_SEND_MS, DEFAULT_SLEEP_BETWEEN_SEND_MS);
    int numProducerThreads = config.getInt(CFG_NUM_PRODUCER_THREADS, DEFAULT_NUM_PRODUCER_THREADS);
    _executor = Executors.newFixedThreadPool(numProducerThreads);
    _randomValueGenerator = new RandomValueGenerator(System.currentTimeMillis());
    try {
      _hostName = InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      LOG.error("getLocalhost threw an exception", e);
      throw new DatastreamRuntimeException(e);
    }
  }

  @Override
  public void start() {
    LOG.info("Start called.");
  }

  @Override
  public void stop() {
    LOG.info("Stop called.");
    _executor.shutdownNow();
    while (!_executor.isTerminated()) {
      Thread.yield();
    }
  }

  @Override
  public void onAssignmentChange(List<DatastreamTask> tasks) {
    LOG.info(String.format("onAssignmentChange called with tasks %s, existing assignment %s", tasks,
        _tasksAssigned.keySet()));
    for (DatastreamTask task : tasks) {
      if (_tasksAssigned.containsKey(task)) {
        continue;
      }

      Future<?> future = _executor.submit((Runnable) () -> executeTask(task));

      _tasksAssigned.put(task, future);
    }

    _tasksAssigned.entrySet().stream().filter(x -> !tasks.contains(x.getKey())).forEach((x) -> {
      LOG.info(String.format("Task %s is reassigned from the current instance, cancelling the producer", x.getKey()));
      x.getValue().cancel(true);
      while (!x.getValue().isDone()) {
        Thread.yield();
      }

      LOG.info(String.format("Producer corresponding to the task %s has been stopped", x.getKey()));
    });
  }

  private void executeTask(DatastreamTask task) {
    try {
      Datastream datastream = task.getDatastreams().get(0);
      int messageSize = _messageSize;
      long sleepBetweenSendMs = _sleepBetweenSendMs;
      String checkpoint = task.getCheckpoints().getOrDefault(task.getPartitions().get(0), "0");

      long index = Long.parseLong(StringUtils.isBlank(checkpoint) ? "0" : checkpoint) + 1;

      LOG.info("Checkpoint string = " + checkpoint + " index = " + index);

      if (datastream.hasMetadata()) {
        StringMap dsMetadata = datastream.getMetadata();
        if (dsMetadata.containsKey(CFG_MESSAGE_SIZE_BYTES)) {
          messageSize = Integer.parseInt(dsMetadata.get(CFG_MESSAGE_SIZE_BYTES));
        }
        if (dsMetadata.containsKey(CFG_SLEEP_BETWEEN_SEND_MS)) {
          sleepBetweenSendMs = Long.parseLong(dsMetadata.get(CFG_SLEEP_BETWEEN_SEND_MS));
        }
      }

      while (true) {
        for (int partition : task.getPartitions()) {
          DatastreamProducerRecord record = createDatastreamEvent(index, messageSize, partition);
          task.getEventProducer().send(record, (metadata, exception) -> {
            if (exception != null) {
              LOG.info("metadata is " + metadata.toString());
              LOG.error("Send failed for event " + metadata.getCheckpoint(), exception);
            }
          });
          try {
            Thread.sleep(sleepBetweenSendMs);
          } catch (InterruptedException e) {
            String msg = "Producer thread is interrupted. Stopping the producer for task " + task;
            LOG.error(msg, e);
            throw new DatastreamRuntimeException(msg, e);
          }
        }

        index++;
      }
    } catch (Exception ex) {
      LOG.error("Producer thread threw exception, Stopping event producer for task " + task, ex);
    }
  }

  private DatastreamProducerRecord createDatastreamEvent(long eventIndex, int messageSize, int partition) {
    DatastreamEvent event = new DatastreamEvent();
    event.key = ByteBuffer.wrap(String.valueOf(eventIndex).getBytes());
    String randomString = _randomValueGenerator.getNextString(messageSize, messageSize);
    String payload =
        String.format("TestEvent for partition:%d from host:%s with event index: %d text: %s", partition, _hostName,
            eventIndex, randomString);
    event.payload = ByteBuffer.wrap(payload.getBytes());
    DatastreamProducerRecordBuilder builder = new DatastreamProducerRecordBuilder();
    builder.addEvent(event);
    builder.setPartition(partition);
    builder.setSourceCheckpoint(String.valueOf(eventIndex));
    builder.setEventsTimestamp(System.currentTimeMillis());
    return builder.build();
  }

  @Override
  public void initializeDatastream(Datastream stream, List<Datastream> allDatastreams)
      throws DatastreamValidationException {
    LOG.info(String.format("initialize called for datastream %s with datastreams %s", stream, allDatastreams));
  }

  @Override
  public Map<String, Metric> getMetrics() {
    return Collections.emptyMap();
  }
}