/**
 *  AbstractBrokerListener
 *  Copyright 1.06.2017 by Michael Peter Christen, @orbiterlab
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program in the file lgpl21.txt
 *  If not, see <http://www.gnu.org/licenses/>.
 */

package net.yacy.grid.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import ai.susi.mind.SusiAction;
import ai.susi.mind.SusiThought;
import net.yacy.grid.Services;
import net.yacy.grid.YaCyServices;
import net.yacy.grid.io.messages.AvailableContainer;
import net.yacy.grid.io.messages.GridBroker;
import net.yacy.grid.io.messages.GridQueue;
import net.yacy.grid.io.messages.MessageContainer;
import net.yacy.grid.tools.Logger;
import net.yacy.grid.tools.Memory;

public abstract class AbstractBrokerListener implements BrokerListener {

    public boolean shallRun;
    private final Services service;
    private final GridQueue[] sourceQueues;
    private final int threadCount;
    private final List<QueueListener> threads;
    private final AtomicInteger targetFill;

    public AbstractBrokerListener(final Services service, final int threadCount) {
        this.service = service;
        this.sourceQueues = service.getSourceQueues();
        this.threadCount = threadCount;
        //    this.threadPool = (ThreadPoolExecutor) Executors.newFixedThreadPool(this.threads);
        this.shallRun = true;
        this.threads = new ArrayList<>();
        this.targetFill = new AtomicInteger(0);
    }

    @Override
    public abstract ActionResult processAction(SusiAction action, JSONArray data, String processName, int processNumber);

    @Override
    public void run() {
        // recover unacknowledged entries - possibly from last start
        for (GridQueue queue: this.sourceQueues) {
            try {
                Data.gridBroker.recover(AbstractBrokerListener.this.service, queue);
            } catch (IOException e) {
                Logger.error(this.getClass(), "Service " + this.service.name() + ": recover not possible: " + e.getMessage(), e);
            }
        }

        // print out some stats about the queues
        try {
            AvailableContainer[] ac = Data.gridBroker.available(AbstractBrokerListener.this.service, this.sourceQueues);
            for (int i = 0; i < ac.length; i++) {
                Logger.info(this.getClass(), "Service " + this.service.name() + ", queue " + ac[i].getQueue() + ": " + ac[i].getAvailable() + " entries.");
            }
        } catch (IOException e) {
            Logger.error(this.getClass(), "Service " + this.service.name() + ": AvailableContainer not available: " + e.getMessage(), e);
        }

        // start the listeners
        int threadsPerQueue = Math.max(1, this.threadCount / this.sourceQueues.length);
        Logger.info(this.getClass(), "Broker Listener: starting " + threadsPerQueue + " threads for each of the " + this.sourceQueues.length + " queues");
        for (GridQueue queue: this.sourceQueues) {
            for (int qc = 0; qc < threadsPerQueue; qc++) {
                QueueListener listener = new QueueListener(queue, qc, Data.gridBroker.isAutoAck(), Data.gridBroker.getQueueThrottling());
                listener.start();
                this.threads.add(listener);
                Logger.info(this.getClass(), "Broker Listener for service " + this.service.name() + ", queue " + queue + " started thread " + qc);
            }
        }

        // start the caretaker
        Caretaker caretaker = new Caretaker();
        caretaker.run();

        // wait for termination, this happens when terminate() is called
        this.threads.forEach(thread -> {
            try {
                thread.join();
                Logger.info(this.getClass(), "Broker Listener for service " + this.service.name() + ", queue " + thread.queueName + " terminated");
            } catch (InterruptedException e) {
                Logger.warn(this.getClass(), "Broker Listener for service " + this.service.name() + ", queue " + thread.queueName + " interrupted", e);
            }
        });
        try {
            caretaker.join();
        } catch (InterruptedException e) {
            Logger.warn(this.getClass(), "Broker Listener for service " + this.service.name() + ", caretaker interrupted", e);
        }
    }

    @Override
    public int messagesPerMinute() {
        int mpm = 0;
        for (QueueListener ql: this.threads) {
            mpm += ql.messagesPerMinute();
        }
        return mpm;
    }

    private class Caretaker extends Thread {

        @Override
        public void run() {
            while (AbstractBrokerListener.this.shallRun) {
                // collect size of target queues:
                int targetQueueAggregator = 0;
                for (Services targetService: AbstractBrokerListener.this.service.getTargetServices()) {
                    for (GridQueue targetQueue: targetService.getSourceQueues()) try {
                        AvailableContainer a = Data.gridBroker.available(targetService, targetQueue);
                        targetQueueAggregator += a.getAvailable();
                    } catch (IOException e) {}
                }
                AbstractBrokerListener.this.targetFill.set(targetQueueAggregator);
                Logger.info(this.getClass(), "BrokerListener operates with " + AbstractBrokerListener.this.messagesPerMinute() + " messages per minute; target queues size: " + targetQueueAggregator);

                // wait a bit
                try {Thread.sleep(60000);} catch (InterruptedException ee) {}
            }
        }
    }

    private class QueueListener extends Thread {
        private final GridQueue queueName;
        private final int threadCounter;
        private final boolean autoAck;
        private final LinkedList<Long> tracker;
        private final long targetQueueThrottling;

        public QueueListener(final GridQueue queueName, final int threadCounter, final boolean autoAck, int queueThrottling) {
            this.queueName = queueName;
            this.threadCounter = threadCounter;
            this.autoAck = autoAck;
            this.targetQueueThrottling = queueThrottling;
            this.tracker = new LinkedList<>();
        }

        public int messagesPerMinute() {
            return this.tracker.size();
        }

        @Override
        public void run() {
            try {
                AvailableContainer a = Data.gridBroker.available(AbstractBrokerListener.this.service, this.queueName);
                Logger.info(this.getClass(), "Started QueueListener for Queue " + a.getQueue() + ", thread " + this.threadCounter + ": " + a.getAvailable() + " entries.");
            } catch (IOException e) {
                Logger.error(this.getClass(), "Could not load AvailableContainer for Queue " + this.queueName + ": " + e.getMessage(), e);
            }

            while (AbstractBrokerListener.this.shallRun) {
                if (Data.gridBroker == null) {
                    try {Thread.sleep(1000);} catch (InterruptedException ee) {}
                    continue; // wait until initialization complete
                }
                String payload = "";
                MessageContainer<byte[]> mc = null;
                ActionResult result = ActionResult.SUCCESS;
                try {
                    // check short memory status
                    if (Memory.shortStatus()) {
                        Logger.info(this.getClass(), "AbstractBrokerListener.QueueListener short memory status: assigned = " + Memory.assigned() + ", used = " + Memory.used());
                        Data.clearCaches();
                    }

                    // check target throttling
                    if (this.targetQueueThrottling > 0) {
                        long throttlingStart = this.targetQueueThrottling / 10 * 9;
                        long targetQueueAggregator = AbstractBrokerListener.this.targetFill.get();
                        if (targetQueueAggregator > throttlingStart) {
                            Logger.info(this.getClass(), "AbstractBrokerListener.QueueListener target queue aggregated size = " + targetQueueAggregator + ", throttling start is " + throttlingStart);
                            long throttlingTime = Math.min(10000L, (targetQueueAggregator - throttlingStart) / this.targetQueueThrottling * 100000L);
                            if (throttlingTime > 1000L) {
                                Logger.info(this.getClass(), "AbstractBrokerListener.QueueListener throttling = " + this.targetQueueThrottling + ", sleeping for " + throttlingTime + " milliseconds");
                                try {Thread.sleep(throttlingTime);} catch (InterruptedException e) {}
                            }
                        }
                    }

                    // wait until message arrives
                    mc = Data.gridBroker.receive(AbstractBrokerListener.this.service, this.queueName, 10000, this.autoAck);
                    if (mc != null && mc.getPayload() != null && mc.getPayload().length > 0) {
                        result = handleMessage(mc, this.queueName.name(), this.threadCounter);
                        // track number of handles messages
                        long time = System.currentTimeMillis();
                        this.tracker.add(time);
                        time = time - 60000;
                        while (this.tracker.size() > 0 && this.tracker.getFirst() < time) this.tracker.removeFirst();
                    }
                    // try {Thread.sleep(1000);} catch (InterruptedException ee) {}
                } catch (JSONException e) {
                    // happens if the payload has a wrong form
                    Logger.warn(this.getClass(), "QueueListener: message syntax error with '" + payload + "' in queue: " + e.getMessage(), e);
                    try {Thread.sleep(10000);} catch (InterruptedException ee) {}
                } catch (Throwable e) {
                    Logger.warn(this.getClass(), "QueueListener: " + e.getMessage(), e);
                    try {Thread.sleep(10000);} catch (InterruptedException ee) {}
                    String m = e.getMessage();
                    if (m == null) m = e.getCause().getMessage();
                    if (m.equals(GridBroker.TARGET_LIMIT_MESSAGE)) {
                        // do not process message!
                        if (!this.autoAck && mc != null && mc.getDeliveryTag() > 0) {
                            // reject the message
                            try {
                                Data.gridBroker.reject(AbstractBrokerListener.this.service, this.queueName, mc.getDeliveryTag());
                            } catch (IOException ee) {
                                Logger.warn(this.getClass(), "QueueListener: cannot acknowledge queue: " + ee.getMessage(), ee);
                            }
                        }
                    }
                } finally {
                    if (!this.autoAck && mc != null && mc.getDeliveryTag() > 0) {
                        // acknowledge the message
                        try {
                            Data.gridBroker.acknowledge(AbstractBrokerListener.this.service, this.queueName, mc.getDeliveryTag());
                        } catch (IOException e) {
                            Logger.warn(this.getClass(), "QueueListener: cannot acknowledge queue: " + e.getMessage(), e);
                        }
                    }
                }
            }
        }
    }

    private ActionResult handleMessage(final MessageContainer<byte[]> mc, final String processName, final int processNumber) {
        Thread.currentThread().setName(processName + "-" + processNumber + "-running");

        String payload = new String(mc.getPayload(), StandardCharsets.UTF_8);
        JSONObject json = new JSONObject(new JSONTokener(payload));
        final SusiThought process = new SusiThought(json);
        final JSONArray data = process.getData();
        final List<SusiAction> actions = process.getActions();

        // loop though all actions
        boolean fail_irreversible = false;
        boolean fail_retry = false;
        actionloop: for (int ac = 0; ac < actions.size(); ac++) {
            SusiAction action = actions.get(ac);
            String type = action.getStringAttr("type");
            String queue = action.getStringAttr("queue");

            // check if the credentials to execute the queue are valid
            if (type == null || type.length() == 0 || queue == null || queue.length() == 0) {
                Logger.info(this.getClass(), "bad message in queue, continue");
                continue actionloop;
            }

            // check if this is the correct queue
            if (!type.equals(this.service.name())) {
                Logger.info(this.getClass(), "wrong message in queue: " + type + ", continue");
                try {
                    loadNextAction(action, process.getData()); // put that into the correct queue
                } catch (Throwable e) {
                    if (e.getMessage().equals(GridBroker.TARGET_LIMIT_MESSAGE)) return ActionResult.FAIL_RETRY;
                    Logger.warn(this.getClass(), e);
                }
                continue actionloop;
            }

            // process the action using the previously acquired execution thread
            ActionResult processed = processAction(action, data, processName, processNumber);
            if (processed == ActionResult.SUCCESS) {
                // send next embedded action(s) to queue
                JSONObject ao = action.toJSONClone();
                if (ao.has("actions")) {
                    JSONArray embeddedActions = ao.getJSONArray("actions");
                    for (int j = 0; j < embeddedActions.length(); j++) {
                        try {
                            loadNextAction(new SusiAction(embeddedActions.getJSONObject(j)), data);
                        } catch (UnsupportedOperationException | JSONException e) {
                            Logger.warn(this.getClass(), e);
                        } catch (IOException e) {
                            if (e.getMessage().equals(GridBroker.TARGET_LIMIT_MESSAGE)) return ActionResult.FAIL_RETRY;
                            Logger.warn(this.getClass(), e);
                            // do a re-try
                            try {Thread.sleep(10000);} catch (InterruptedException e1) {}
                            try {
                                loadNextAction(new SusiAction(embeddedActions.getJSONObject(j)), data);
                            } catch (UnsupportedOperationException | JSONException | IOException ee) {
                                Logger.warn(this.getClass(), e);
                            }
                        }
                    }
                }
            }
            if (processed == ActionResult.FAIL_RETRY) fail_retry = true;
            if (processed == ActionResult.FAIL_IRREVERSIBLE) fail_irreversible = true;
        }
        if (fail_irreversible) return ActionResult.FAIL_IRREVERSIBLE;
        if (fail_retry) return ActionResult.FAIL_RETRY;
        return ActionResult.SUCCESS;
    }

    private void loadNextAction(SusiAction action, JSONArray data) throws UnsupportedOperationException, IOException {
        String type = action.getStringAttr("type");
        if (type == null || type.length() == 0) throw new UnsupportedOperationException("missing type in action");
        String queue = action.getStringAttr("queue");
        if (queue == null || queue.length() == 0) throw new UnsupportedOperationException("missing queue in action");

        // create a new Thought and push it to the next queue
        JSONObject nextProcess = new JSONObject()
                .put("data", data)
                .put("actions", new JSONArray().put(action.toJSONClone()));
        byte[] b = nextProcess.toString(2).getBytes(StandardCharsets.UTF_8);
        Data.gridBroker.send(YaCyServices.valueOf(type), new GridQueue(queue), b);
    }

    @Override
    public void terminate() {
        this.shallRun = false;
    }

}