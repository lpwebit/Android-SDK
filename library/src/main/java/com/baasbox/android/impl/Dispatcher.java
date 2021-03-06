/*
 * Copyright (C) 2014. BaasBox
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package com.baasbox.android.impl;

import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import com.baasbox.android.BaasBox;
import com.baasbox.android.BaasHandler;
import com.baasbox.android.BaasResult;
import com.baasbox.android.ExceptionHandler;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by Andrea Tortorella on 20/01/14.
 */
public final class Dispatcher {
// ------------------------------ FIELDS ------------------------------

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    final Handler defaultMainHandler = new Handler(Looper.getMainLooper());


    private final PriorityBlockingQueue<Task<?>> taskQueue;
    private final ConcurrentMap<Integer, Task<?>> liveAsyncs;
    private final ExceptionHandler exceptionHandler;
    private final Worker[] workers;
    private final BaasBox box;
    private volatile boolean quit;

// --------------------------- CONSTRUCTORS ---------------------------
    public Dispatcher(BaasBox box) {
        this.box = box;
        this.exceptionHandler = setHandler(box.config.exceptionHandler);
        this.workers = createWorkers(box.config.workerThreads);
        this.taskQueue = new PriorityBlockingQueue<Task<?>>(16);
        this.liveAsyncs = new ConcurrentHashMap<Integer, Task<?>>(16, 0.75f, 1);
    }

    private static ExceptionHandler setHandler(ExceptionHandler handler) {
        if (handler == null) {
            Logger.warn("exceptionHandler is null: set using default");
            return ExceptionHandler.DEFAULT;
        }
        return handler;
    }

    private static Worker[] createWorkers(int threads) {
        if (threads < 0) {
            Logger.warn("Ignoring workerThreads: less than 0 threads, default will be used");
            threads = 0;
        }
        if (threads == 0) {
            threads = Runtime.getRuntime().availableProcessors();
            Logger.info("Using default number of threads configuration %s", threads);
        }
        return new Worker[threads];
    }

// -------------------------- OTHER METHODS --------------------------

    public <R> BaasResult<R> await(int requestId) {
        Task<R> task = (Task<R>) liveAsyncs.get(requestId);
        if (task == null) {
            return null;
        } else if (task.result != null) {
            return task.result;
        } else {
            task.await();
            return task.result;
        }
    }

    public boolean cancel(int requestId, boolean immediate) {
        Task<?> task = liveAsyncs.get(requestId);
        if (task == null) return false;
        if (immediate) {
            return task.abort();
        } else {
            return task.cancel();
        }
    }

    void finish(Task<?> req) {
        this.liveAsyncs.remove(req.seqNumber, req);
        Logger.info("%s finished", req);
    }

    public int post(Task<?> request) {
        final int seqNumber = SEQUENCE.getAndIncrement();
        request.bind(seqNumber, this);
        liveAsyncs.put(seqNumber, request);
        taskQueue.add(request);
        return seqNumber;
    }

    public <R> boolean resume(int requestId, BaasHandler<R> handler) {
        Task<R> task = (Task<R>) liveAsyncs.get(requestId);
        if (task == null) {
            return false;
        }
        return task.resume(handler);
    }

    public void start() {
        stop();
        quit = false;
        for (int i = 0; i < workers.length; i++) {
            workers[i] = new Worker(this);
            workers[i].start();
        }
    }

    public void stop() {
        quit = true;
        for (int i = 0; i < workers.length; i++) {
            if (workers[i] != null) {
                workers[i].interrupt();
                workers[i] = null;
            }
        }
    }

    public boolean suspend(int requestId) {
        Task<?> task = liveAsyncs.get(requestId);
        return task != null && task.suspend();
    }

// -------------------------- INNER CLASSES --------------------------

    private static final class Worker extends Thread {
        private final PriorityBlockingQueue<Task<?>> queue;
        private final Dispatcher dispatcher;

        Worker(Dispatcher dispatcher) {
            this.dispatcher = dispatcher;
            this.queue = dispatcher.taskQueue;
        }

        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            Task<?> task;
            while (true) {
                try {
                    task = queue.take();
                } catch (InterruptedException e) {
                    if (dispatcher.quit) return;
                    continue;
                }
                try {
                    task.execute();
                    task.post();
                    task.unlock();
                } catch (Exception t) {
                    if (dispatcher.exceptionHandler.onError(t)) {
                        Logger.error(t,"Dispatcher error");
                    }
                }
            }
        }
    }
}
