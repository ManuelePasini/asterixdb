/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.asterix.external.adapter.factory;

import java.util.Collections;
import java.util.Map;

import org.apache.asterix.common.api.INcApplicationContext;
import org.apache.asterix.common.dataflow.ICcApplicationContext;
import org.apache.asterix.common.exceptions.AsterixException;
import org.apache.asterix.common.external.IDataSourceAdapter;
import org.apache.asterix.common.external.IExternalFilterEvaluatorFactory;
import org.apache.asterix.common.library.ILibraryManager;
import org.apache.asterix.common.metadata.DataverseName;
import org.apache.asterix.external.api.IDataFlowController;
import org.apache.asterix.external.api.IDataParserFactory;
import org.apache.asterix.external.api.IExternalDataSourceFactory;
import org.apache.asterix.external.api.ITypedAdapterFactory;
import org.apache.asterix.external.dataflow.AbstractFeedDataFlowController;
import org.apache.asterix.external.dataset.adapter.FeedAdapter;
import org.apache.asterix.external.dataset.adapter.GenericAdapter;
import org.apache.asterix.external.input.filter.NoOpExternalFilterEvaluatorFactory;
import org.apache.asterix.external.provider.DataflowControllerProvider;
import org.apache.asterix.external.provider.DatasourceFactoryProvider;
import org.apache.asterix.external.provider.ParserFactoryProvider;
import org.apache.asterix.external.util.ExternalDataCompatibilityUtils;
import org.apache.asterix.external.util.ExternalDataConstants;
import org.apache.asterix.external.util.ExternalDataUtils;
import org.apache.asterix.external.util.FeedLogManager;
import org.apache.asterix.external.util.FeedUtils;
import org.apache.asterix.external.util.IFeedLogManager;
import org.apache.asterix.external.util.NoOpFeedLogManager;
import org.apache.asterix.om.types.ARecordType;
import org.apache.hyracks.algebricks.common.constraints.AlgebricksAbsolutePartitionConstraint;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.api.application.ICCServiceContext;
import org.apache.hyracks.api.application.INCServiceContext;
import org.apache.hyracks.api.application.IServiceContext;
import org.apache.hyracks.api.context.IHyracksTaskContext;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.api.exceptions.IWarningCollector;
import org.apache.hyracks.api.io.FileSplit;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class GenericAdapterFactory implements ITypedAdapterFactory {

    private static final long serialVersionUID = 2L;
    private static final Logger LOGGER = LogManager.getLogger();
    private IExternalDataSourceFactory dataSourceFactory;
    private IDataParserFactory dataParserFactory;
    private ARecordType recordType;
    private Map<String, String> configuration;
    private boolean isFeed;
    private boolean logIngestionEvents;
    private FileSplit[] feedLogFileSplits;
    private ARecordType metaType;
    private transient IFeedLogManager feedLogManager;

    @Override
    public String getAlias() {
        return ExternalDataConstants.ALIAS_GENERIC_ADAPTER;
    }

    @Override
    public AlgebricksAbsolutePartitionConstraint getPartitionConstraint() throws AlgebricksException {
        return dataSourceFactory.getPartitionConstraint();
    }

    /**
     * Runs on each node controller (after serialization-deserialization)
     */
    @Override
    public synchronized IDataSourceAdapter createAdapter(IHyracksTaskContext ctx, int partition)
            throws HyracksDataException {
        INCServiceContext serviceCtx = ctx.getJobletContext().getServiceContext();
        INcApplicationContext appCtx = (INcApplicationContext) serviceCtx.getApplicationContext();
        try {
            restoreExternalObjects(serviceCtx, appCtx.getLibraryManager(), ctx.getWarningCollector());
        } catch (Exception e) {
            LOGGER.log(Level.INFO, "Failure restoring external objects", e);
            throw HyracksDataException.create(e);
        }
        if (isFeed && feedLogManager == null) {
            if (logIngestionEvents) {
                feedLogManager =
                        new FeedLogManager(feedLogFileSplits[partition].getFileReference(ctx.getIoManager()).getFile());
                feedLogManager.touch();
            } else {
                feedLogManager = NoOpFeedLogManager.INSTANCE;
            }
        }
        IDataFlowController controller = DataflowControllerProvider.getDataflowController(recordType, ctx, partition,
                dataSourceFactory, dataParserFactory, configuration, isFeed, feedLogManager);
        if (isFeed) {
            return new FeedAdapter((AbstractFeedDataFlowController) controller);
        } else {
            return new GenericAdapter(controller);
        }
    }

    private void restoreExternalObjects(IServiceContext serviceContext, ILibraryManager libraryManager,
            IWarningCollector warningCollector) throws HyracksDataException, AlgebricksException {
        if (dataSourceFactory == null) {
            dataSourceFactory = createExternalDataSourceFactory(configuration);
            // create and configure parser factory
            dataSourceFactory.configure(serviceContext, configuration, warningCollector,
                    NoOpExternalFilterEvaluatorFactory.INSTANCE);
        }
        if (dataParserFactory == null) {
            // create and configure parser factory
            dataParserFactory = createDataParserFactory(configuration);
            dataParserFactory.setRecordType(recordType);
            dataParserFactory.setMetaType(metaType);
            dataParserFactory.configure(configuration);
        }
    }

    @Override
    public void configure(ICCServiceContext serviceContext, Map<String, String> configuration,
            IWarningCollector warningCollector, IExternalFilterEvaluatorFactory filterEvaluatorFactory)
            throws HyracksDataException, AlgebricksException {
        this.configuration = configuration;
        ICcApplicationContext appCtx = (ICcApplicationContext) serviceContext.getApplicationContext();
        ExternalDataUtils.validateDataSourceParameters(configuration);
        dataSourceFactory = createExternalDataSourceFactory(configuration);
        dataSourceFactory.configure(serviceContext, configuration, warningCollector, filterEvaluatorFactory);
        ExternalDataUtils.validateDataParserParameters(configuration);
        dataParserFactory = createDataParserFactory(configuration);
        dataParserFactory.setRecordType(recordType);
        dataParserFactory.setMetaType(metaType);
        dataParserFactory.configure(configuration);
        ExternalDataCompatibilityUtils.validateCompatibility(dataSourceFactory, dataParserFactory);
        configureFeedLogManager(appCtx);
        nullifyExternalObjects();
    }

    private void configureFeedLogManager(ICcApplicationContext appCtx)
            throws HyracksDataException, AlgebricksException {
        this.isFeed = ExternalDataUtils.isFeed(configuration);
        this.logIngestionEvents = ExternalDataUtils.isLogIngestionEvents(configuration);
        if (logIngestionEvents) {
            DataverseName dataverseName = ExternalDataUtils.getDatasetDataverse(configuration);
            String databaseName = ExternalDataUtils.getDatasetDatabase(configuration);
            String namespacePath = appCtx.getNamespacePathResolver().resolve(databaseName, dataverseName);
            //TODO(partitioning) make this code reuse DataPartitioningProvider
            feedLogFileSplits = FeedUtils.splitsForAdapter(namespacePath, ExternalDataUtils.getFeedName(configuration),
                    dataSourceFactory.getPartitionConstraint());
        }
    }

    private void nullifyExternalObjects() {
        if (ExternalDataUtils.isExternal(configuration.get(ExternalDataConstants.KEY_READER))) {
            dataSourceFactory = null;
        }
        if (ExternalDataUtils.isExternal(configuration.get(ExternalDataConstants.KEY_PARSER))) {
            dataParserFactory = null;
        }
    }

    @Override
    public ARecordType getOutputType() {
        return recordType;
    }

    @Override
    public void setOutputType(ARecordType recordType) {
        this.recordType = recordType;
    }

    @Override
    public ARecordType getMetaType() {
        return metaType;
    }

    @Override
    public void setMetaType(ARecordType metaType) {
        this.metaType = metaType;
    }

    /**
     * used by extensions to access shared datasource factory for a job
     *
     * @return the data source factory
     */
    public IExternalDataSourceFactory getDataSourceFactory() {
        return dataSourceFactory;
    }

    /**
     * Use pre-configured datasource factory For function datasources
     *
     * @param dataSourceFactory the function datasource factory
     * @param dataParserFactory the function data parser factory
     * @throws AlgebricksException
     */
    public void configure(IExternalDataSourceFactory dataSourceFactory, IDataParserFactory dataParserFactory)
            throws AlgebricksException {
        this.dataSourceFactory = dataSourceFactory;
        this.dataParserFactory = dataParserFactory;
        configuration = Collections.emptyMap();
    }

    protected IExternalDataSourceFactory createExternalDataSourceFactory(Map<String, String> configuration)
            throws HyracksDataException, AsterixException {
        return DatasourceFactoryProvider.getExternalDataSourceFactory(configuration);
    }

    protected IDataParserFactory createDataParserFactory(Map<String, String> configuration) throws AsterixException {
        return ParserFactoryProvider.getDataParserFactory(configuration);
    }
}
