/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.distributedlog.statestore.impl.mvcc;

import static org.apache.distributedlog.statestore.impl.mvcc.MVCCUtils.toCommand;

import com.google.common.annotations.VisibleForTesting;
import io.netty.buffer.ByteBuf;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.apache.bookkeeper.common.concurrent.FutureUtils;
import org.apache.distributedlog.api.namespace.Namespace;
import org.apache.distributedlog.api.statestore.exceptions.InvalidStateStoreException;
import org.apache.distributedlog.api.statestore.mvcc.MVCCAsyncStore;
import org.apache.distributedlog.api.statestore.mvcc.op.DeleteOp;
import org.apache.distributedlog.api.statestore.mvcc.op.OpFactory;
import org.apache.distributedlog.api.statestore.mvcc.op.PutOp;
import org.apache.distributedlog.api.statestore.mvcc.op.RangeOp;
import org.apache.distributedlog.api.statestore.mvcc.op.TxnOp;
import org.apache.distributedlog.api.statestore.mvcc.result.DeleteResult;
import org.apache.distributedlog.api.statestore.mvcc.result.PutResult;
import org.apache.distributedlog.api.statestore.mvcc.result.RangeResult;
import org.apache.distributedlog.api.statestore.mvcc.result.TxnResult;
import org.apache.distributedlog.statestore.impl.journal.AbstractStateStoreWithJournal;
import org.apache.distributedlog.statestore.impl.journal.CommandProcessor;
import org.apache.distributedlog.statestore.proto.Command;

/**
 * MVCC Async Store Implementation.
 */
@Slf4j
class MVCCAsyncBytesStoreImpl
        extends AbstractStateStoreWithJournal<MVCCStoreImpl<byte[], byte[]>>
        implements MVCCAsyncStore<byte[], byte[]> {

    MVCCAsyncBytesStoreImpl(Supplier<MVCCStoreImpl<byte[], byte[]>> storeSupplier,
                            Supplier<Namespace> namespaceSupplier) {
        super(storeSupplier, namespaceSupplier);
    }

    @Override
    protected ByteBuf newCatchupMarker() {
        return MVCCUtils.newLogRecordBuf(MVCCUtils.NOP_CMD);
    }

    @Override
    protected CommandProcessor<MVCCStoreImpl<byte[], byte[]>> newCommandProcessor() {
        return MVCCCommandProcessor.of();
    }

    @VisibleForTesting
    @Override
    public boolean ownWriteScheduler() {
        return super.ownWriteScheduler();
    }

    @VisibleForTesting
    @Override
    public boolean ownReadScheduler() {
        return super.ownReadScheduler();
    }

    @Override
    public OpFactory<byte[], byte[]> getOpFactory() {
        return localStore.getOpFactory();
    }

    private CompletableFuture<Long> writeCommandReturnTxId(Command command) {
        ByteBuf recordBuf = MVCCUtils.newLogRecordBuf(command);
        return writeCommandBufReturnTxId(recordBuf);
    }

    @Override
    public CompletableFuture<RangeResult<byte[], byte[]>> range(RangeOp<byte[], byte[]> rangeOp) {
        synchronized (this) {
            if (!isInitialized) {
                return FutureUtils.exception(new InvalidStateStoreException("State store is not initialized yet."));
            }
        }

        // consider running read operations in separate I/O threads
        return executeReadIO(() -> localStore.range(rangeOp));
    }

    @Override
    public CompletableFuture<PutResult<byte[], byte[]>> put(PutOp<byte[], byte[]> op) {
        synchronized (this) {
            if (!isInitialized) {
                return FutureUtils.exception(new InvalidStateStoreException("State store is not initialized yet."));
            }
        }

        Command command = toCommand(op);
        return writeCommandReturnTxId(command)
            .thenApplyAsync(revision -> localStore.put(revision, op), writeIOScheduler);
    }

    @Override
    public CompletableFuture<DeleteResult<byte[], byte[]>> delete(DeleteOp<byte[], byte[]> op) {
        synchronized (this) {
            if (!isInitialized) {
                return FutureUtils.exception(new InvalidStateStoreException("State store is not initialized yet."));
            }
        }

        Command command = toCommand(op);
        return writeCommandReturnTxId(command)
            .thenApplyAsync(revision -> localStore.delete(revision, op), writeIOScheduler);
    }

    @Override
    public CompletableFuture<TxnResult<byte[], byte[]>> txn(TxnOp<byte[], byte[]> op) {
        synchronized (this) {
            if (!isInitialized) {
                return FutureUtils.exception(new InvalidStateStoreException("State store is not initialized yet."));
            }
        }

        Command command = toCommand(op);
        return writeCommandReturnTxId(command)
            .thenApplyAsync(revision -> localStore.txn(revision, op), writeIOScheduler);
    }
}
