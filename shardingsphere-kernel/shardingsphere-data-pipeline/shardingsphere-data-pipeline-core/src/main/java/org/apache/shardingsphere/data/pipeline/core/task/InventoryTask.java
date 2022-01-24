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

package org.apache.shardingsphere.data.pipeline.core.task;

import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.data.pipeline.api.config.ingest.InventoryDumperConfiguration;
import org.apache.shardingsphere.data.pipeline.api.config.rulealtered.ImporterConfiguration;
import org.apache.shardingsphere.data.pipeline.api.executor.AbstractLifecycleExecutor;
import org.apache.shardingsphere.data.pipeline.api.ingest.channel.PipelineChannel;
import org.apache.shardingsphere.data.pipeline.api.ingest.position.IngestPosition;
import org.apache.shardingsphere.data.pipeline.api.ingest.position.PlaceholderPosition;
import org.apache.shardingsphere.data.pipeline.api.ingest.record.Record;
import org.apache.shardingsphere.data.pipeline.api.task.progress.InventoryTaskProgress;
import org.apache.shardingsphere.data.pipeline.core.datasource.PipelineDataSourceManager;
import org.apache.shardingsphere.data.pipeline.core.exception.PipelineJobExecutionException;
import org.apache.shardingsphere.data.pipeline.core.execute.ExecuteCallback;
import org.apache.shardingsphere.data.pipeline.core.execute.ExecuteEngine;
import org.apache.shardingsphere.data.pipeline.spi.importer.Importer;
import org.apache.shardingsphere.data.pipeline.spi.ingest.channel.PipelineChannelFactory;
import org.apache.shardingsphere.data.pipeline.spi.ingest.dumper.Dumper;
import org.apache.shardingsphere.scaling.core.job.dumper.DumperFactory;
import org.apache.shardingsphere.scaling.core.job.importer.ImporterFactory;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Inventory task.
 */
@Slf4j
@ToString(exclude = {"importerExecuteEngine", "dataSourceManager", "dumper"})
public final class InventoryTask extends AbstractLifecycleExecutor implements PipelineTask, AutoCloseable {
    
    @Getter
    private final String taskId;
    
    private final InventoryDumperConfiguration inventoryDumperConfig;
    
    private final ImporterConfiguration importerConfig;
    
    private final PipelineChannelFactory pipelineChannelFactory;
    
    private final ExecuteEngine importerExecuteEngine;
    
    private final PipelineDataSourceManager dataSourceManager;
    
    private Dumper dumper;
    
    private volatile IngestPosition<?> position;
    
    public InventoryTask(final InventoryDumperConfiguration inventoryDumperConfig, final ImporterConfiguration importerConfig,
                         final PipelineChannelFactory pipelineChannelFactory, final ExecuteEngine importerExecuteEngine) {
        this.inventoryDumperConfig = inventoryDumperConfig;
        this.importerConfig = importerConfig;
        this.pipelineChannelFactory = pipelineChannelFactory;
        this.importerExecuteEngine = importerExecuteEngine;
        this.dataSourceManager = new PipelineDataSourceManager();
        taskId = generateTaskId(inventoryDumperConfig);
        position = inventoryDumperConfig.getPosition();
    }
    
    private String generateTaskId(final InventoryDumperConfiguration inventoryDumperConfig) {
        String result = String.format("%s.%s", inventoryDumperConfig.getDataSourceName(), inventoryDumperConfig.getTableName());
        return null == inventoryDumperConfig.getShardingItem() ? result : result + "#" + inventoryDumperConfig.getShardingItem();
    }
    
    @Override
    public void start() {
        instanceDumper();
        Importer importer = ImporterFactory.newInstance(importerConfig, dataSourceManager);
        instanceChannel(importer);
        Future<?> future = importerExecuteEngine.submit(importer, new ExecuteCallback() {
            
            @Override
            public void onSuccess() {
                log.info("importer onSuccess");
            }
            
            @Override
            public void onFailure(final Throwable throwable) {
                log.error("get an error when migrating the inventory data", throwable);
                dumper.stop();
            }
        });
        dumper.start();
        waitForResult(future);
        log.info("importer future done");
        dataSourceManager.close();
    }
    
    private void instanceDumper() {
        dumper = DumperFactory.newInstanceJdbcDumper(inventoryDumperConfig, dataSourceManager);
    }
    
    private void instanceChannel(final Importer importer) {
        PipelineChannel channel = pipelineChannelFactory.createPipelineChannel(1, records -> {
            Record lastNormalRecord = getLastNormalRecord(records);
            if (null != lastNormalRecord) {
                position = lastNormalRecord.getPosition();
            }
        });
        dumper.setChannel(channel);
        importer.setChannel(channel);
    }
    
    private Record getLastNormalRecord(final List<Record> records) {
        for (int index = records.size() - 1; index >= 0; index--) {
            Record record = records.get(index);
            if (record.getPosition() instanceof PlaceholderPosition) {
                continue;
            }
            return record;
        }
        return null;
    }
    
    private void waitForResult(final Future<?> future) {
        try {
            future.get();
        } catch (final InterruptedException ignored) {
        } catch (final ExecutionException ex) {
            throw new PipelineJobExecutionException(String.format("Task %s execute failed ", taskId), ex.getCause());
        }
    }
    
    @Override
    public void stop() {
        if (null != dumper) {
            dumper.stop();
            dumper = null;
        }
    }
    
    @Override
    public InventoryTaskProgress getProgress() {
        return new InventoryTaskProgress(position);
    }
    
    @Override
    public void close() {
        if (null != dataSourceManager) {
            dataSourceManager.close();
        }
    }
}
