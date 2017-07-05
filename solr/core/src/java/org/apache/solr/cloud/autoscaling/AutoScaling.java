/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.cloud.autoscaling;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import com.google.common.base.Preconditions;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.solr.core.CoreContainer;

public class AutoScaling {

  public enum EventType {
    NODEADDED,
    NODELOST,
    REPLICALOST,
    MANUAL,
    SCHEDULED,
    SEARCHRATE,
    INDEXRATE
  }

  public enum EventProcessorStage {
    WAITING,
    STARTED,
    ABORTED,
    SUCCEEDED,
    FAILED,
    BEFORE_ACTION,
    AFTER_ACTION
  }

  /**
   * Implementation of this interface is used for processing events generated by a trigger.
   */
  public interface EventProcessor {

    /**
     * This method is executed for events produced by {@link Trigger#run()}.
     *
     * @param event a subclass of {@link TriggerEvent}
     * @return true if the processor was ready to perform actions on the event, false
     * otherwise. If false was returned then callers should assume the event was discarded.
     */
    boolean process(TriggerEvent event);
  }

  /**
   * Implementations of this interface are notified of stages in event processing that they were
   * registered for. Note: instances may be closed and re-created on each auto-scaling config update.
   */
  public interface TriggerListener extends Closeable {

    void init(CoreContainer coreContainer, AutoScalingConfig.TriggerListenerConfig config);

    AutoScalingConfig.TriggerListenerConfig getTriggerListenerConfig();

    /**
     * This method is called when either a particular <code>stage</code> or
     * <code>actionName</code> is reached during event processing.
     * @param stage {@link EventProcessorStage} that this listener was registered for, or null
     * @param actionName {@link TriggerAction} name that this listener was registered for, or null
     * @param event current event being processed
     * @param message optional message
     */
    void onEvent(EventProcessorStage stage, String actionName, TriggerEvent event, String message);
  }

  /**
   * Interface for a Solr trigger. Each trigger implements Runnable and Closeable interface. A trigger
   * is scheduled using a {@link java.util.concurrent.ScheduledExecutorService} so it is executed as
   * per a configured schedule to check whether the trigger is ready to fire. The {@link Trigger#setProcessor(EventProcessor)}
   * method should be used to set a processor which is used by implementation of this class whenever
   * ready.
   * <p>
   * As per the guarantees made by the {@link java.util.concurrent.ScheduledExecutorService} a trigger
   * implementation is only ever called sequentially and therefore need not be thread safe. However, it
   * is encouraged that implementations be immutable with the exception of the associated listener
   * which can be get/set by a different thread than the one executing the trigger. Therefore, implementations
   * should use appropriate synchronization around the listener.
   * <p>
   * When a trigger is ready to fire, it calls the {@link EventProcessor#process(TriggerEvent)} event
   * with the proper trigger event object. If that method returns false then it should be interpreted to mean
   * that Solr is not ready to process this trigger event and therefore we should retain the state and fire
   * at the next invocation of the run() method.
   */
  public interface Trigger extends Closeable, Runnable {
    /**
     * Trigger name.
     */
    String getName();

    /**
     * Event type generated by this trigger.
     */
    EventType getEventType();

    /** Returns true if this trigger is enabled. */
    boolean isEnabled();

    /** Trigger properties. */
    Map<String, Object> getProperties();

    /** Number of seconds to wait between fired events ("waitFor" property). */
    int getWaitForSecond();

    /** Actions to execute when event is fired. */
    List<TriggerAction> getActions();

    /** Set event processor to call when event is fired. */
    void setProcessor(EventProcessor processor);

    /** Get event processor. */
    EventProcessor getProcessor();

    /** Return true when this trigger is closed and cannot be used. */
    boolean isClosed();

    /** Set internal state of this trigger from another instance. */
    void restoreState(Trigger old);

    /** Save internal state of this trigger in ZooKeeper. */
    void saveState();

    /** Restore internal state of this trigger from ZooKeeper. */
    void restoreState();

    /**
     * Called before a trigger is scheduled. Any heavy object creation or initialisation should
     * be done in this method instead of the Trigger's constructor.
     */
    void init();
  }

  public static class TriggerFactory implements Closeable {

    private final CoreContainer coreContainer;

    private boolean isClosed = false;

    public TriggerFactory(CoreContainer coreContainer) {
      Preconditions.checkNotNull(coreContainer);
      this.coreContainer = coreContainer;
    }

    public synchronized Trigger create(EventType type, String name, Map<String, Object> props) {
      if (isClosed) {
        throw new AlreadyClosedException("TriggerFactory has already been closed, cannot create new triggers");
      }
      switch (type) {
        case NODEADDED:
          return new NodeAddedTrigger(name, props, coreContainer);
        case NODELOST:
          return new NodeLostTrigger(name, props, coreContainer);
        default:
          throw new IllegalArgumentException("Unknown event type: " + type + " in trigger: " + name);
      }
    }

    @Override
    public void close() throws IOException {
      synchronized (this) {
        isClosed = true;
      }
    }
  }

  public static final String AUTO_ADD_REPLICAS_TRIGGER_DSL =
      "{" +
      "    'set-trigger' : {" +
      "        'name' : '.auto_add_replicas'," +
      "        'event' : 'nodeLost'," +
      "        'waitFor' : '5s'," +
      "        'enabled' : true," +
      "        'actions' : [" +
      "            {" +
      "                'name':'auto_add_replicas_plan'," +
      "                'class':'solr.AutoAddReplicasPlanAction'" +
      "            }," +
      "            {" +
      "                'name':'execute_plan'," +
      "                'class':'solr.ExecutePlanAction'" +
      "            }," +
      "            {" +
      "                'name':'log_plan'," +
      "                'class':'solr.LogPlanAction'" +
      "            }" +
      "        ]" +
      "    }" +
      "}";
}
