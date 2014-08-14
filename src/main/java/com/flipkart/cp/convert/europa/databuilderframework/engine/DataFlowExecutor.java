package com.flipkart.cp.convert.europa.databuilderframework.engine;

import com.flipkart.cp.convert.europa.databuilderframework.model.*;
import com.flipkart.cp.convert.europa.databuilderframework.util.DataSetAccessor;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * The executor for a {@link com.flipkart.cp.convert.europa.databuilderframework.model.DataFlow}.
 */
public class DataFlowExecutor {
    private static final Logger logger = LoggerFactory.getLogger(DataFlowExecutor.class.getSimpleName());

    private List<DataBuilderExecutionListener> dataBuilderExecutionListener;
    private DataBuilderFactory dataBuilderFactory;

    public DataFlowExecutor(DataBuilderFactory dataBuilderFactory) {
        this.dataBuilderExecutionListener = Lists.newArrayList();
        this.dataBuilderFactory = dataBuilderFactory;
    }

    /**
     * It uses {@link com.flipkart.cp.convert.europa.databuilderframework.model.Data} present in the existing
     * {@link com.flipkart.cp.convert.europa.databuilderframework.model.DataSet} and those provided by
     * {@link com.flipkart.cp.convert.europa.databuilderframework.model.DataDelta} to generate more data.
     * {@link com.flipkart.cp.convert.europa.databuilderframework.model.Data} generated by all executors invoked in a request
     * are registerd back into the {@link com.flipkart.cp.convert.europa.databuilderframework.model.DataSet}
     *
     * @param dataFlowInstance An instance of the {@link com.flipkart.cp.convert.europa.databuilderframework.model.DataFlow} to run.
     * @param dataDelta        The set of data to be considered for analysis.
     * @return A response containing responses from every {@link DataBuilder}
     * that was invoked in this stage. Note that these have already been added to the DataSet before returning.
     * @throws DataFrameworkException
     */
    public DataExecutionResponse run(DataFlowInstance dataFlowInstance, DataDelta dataDelta) throws DataFrameworkException {
        DataBuilderContext dataBuilderContext = new DataBuilderContext();
        return run(dataBuilderContext, dataFlowInstance, dataDelta);
    }

    /**
     * It uses {@link com.flipkart.cp.convert.europa.databuilderframework.model.Data} present in the existing
     * {@link com.flipkart.cp.convert.europa.databuilderframework.model.DataSet} and those provided by
     * {@link com.flipkart.cp.convert.europa.databuilderframework.model.DataDelta} to generate more data.
     * {@link com.flipkart.cp.convert.europa.databuilderframework.model.Data} generated by all executors invoked in a request
     * are registerd back into the {@link com.flipkart.cp.convert.europa.databuilderframework.model.DataSet}
     *
     * @param dataBuilderContext An instance of the {@link com.flipkart.cp.convert.europa.databuilderframework.engine.DataBuilderContext} object.
     * @param dataFlowInstance   An instance of the {@link com.flipkart.cp.convert.europa.databuilderframework.model.DataFlow} to run.
     * @param dataDelta          The set of data to be considered for analysis.
     * @return A response containing responses from every {@link DataBuilder}
     * that was invoked in this stage. Note that these have already been added to the DataSet before returning.
     * @throws DataFrameworkException
     */
    public DataExecutionResponse run(DataBuilderContext dataBuilderContext, DataFlowInstance dataFlowInstance, DataDelta dataDelta) throws DataFrameworkException {
        DataFlow dataFlow = dataFlowInstance.getDataFlow();
        ExecutionGraph executionGraph = dataFlow.getExecutionGraph();
        DataSet dataSet = dataFlowInstance.getDataSet().accessor().copy(); //Create own copy to work with
        DataSetAccessor dataSetAccessor = DataSet.accessor(dataSet);
        dataSetAccessor.merge(dataDelta);
        Set<String> alreadyRanBuilders = Sets.newHashSet();
        dataBuilderContext.setDataSet(dataSet);
        Map<String, Data> responseData = Maps.newTreeMap();
        Set<String> activeDataSet = Sets.newHashSet();

        for (Data data : dataDelta.getDelta()) {
            activeDataSet.add(data.getData());
        }
        List<List<DataBuilderMeta>> dependencyHierarchy = executionGraph.getDependencyHierarchy();
        Set<String> newlyGeneratedData = Sets.newHashSet();
        while(true) {
            for (List<DataBuilderMeta> levelBuilders : dependencyHierarchy) {
                for (DataBuilderMeta builderMeta : levelBuilders) {
                    if (builderMeta.isProcessed()) {
                        continue;
                    }
                    Set<String> intersection = new HashSet<String>(builderMeta.getConsumes());
                    intersection.retainAll(activeDataSet);
                    //If there is an intersection, means some of it's inputs have changed. Reevaluate
                    if (intersection.isEmpty()) {
                        continue;
                    }
                    DataBuilder builder = dataBuilderFactory.create(builderMeta.getName());
                    if (!dataSetAccessor.checkForData(builder.getDataBuilderMeta().getConsumes())) {
                        break; //No need to run others, list is topo sorted
                    }
                    for (DataBuilderExecutionListener listener : dataBuilderExecutionListener) {
                        try {
                            listener.beforeExecute(dataFlowInstance, builderMeta, dataDelta, responseData);
                        } catch (Throwable t) {
                            logger.error("Error running pre-execution execution listener: ", t);
                        }
                    }
                    try {
                        Data response = builder.process(dataBuilderContext);
                        if (null != response) {
                            dataSetAccessor.merge(response);
                            responseData.put(builderMeta.getName(), response);
                            activeDataSet.add(response.getData());
                            if(null != dataFlow.getTransients() && !dataFlow.getTransients().contains(response.getData())) {
                                newlyGeneratedData.add(response.getData());
                            }
                        }
                        alreadyRanBuilders.add(builderMeta.getName());
                        logger.info("Ran " + builderMeta.getName());
                        builderMeta.setProcessed(true);
                        for (DataBuilderExecutionListener listener : dataBuilderExecutionListener) {
                            try {
                                listener.afterExecute(dataFlowInstance, builderMeta, dataDelta, responseData, response);
                            } catch (Throwable t) {
                                logger.error("Error running post-execution listener: ", t);
                            }
                        }

                    } catch (DataBuilderException e) {
                        logger.error("Error running builder: " + builderMeta.getName());
                        for (DataBuilderExecutionListener listener : dataBuilderExecutionListener) {
                            try {
                                listener.afterException(dataFlowInstance, builderMeta, dataDelta, responseData, e);

                            } catch (Throwable error) {
                                logger.error("Error running post-execution listener: ", error);
                            }
                        }
                        throw new DataFrameworkException(DataFrameworkException.ErrorCode.BUILDER_EXECUTION_ERROR,
                                "Error running builder: " + builderMeta.getName(), e.getData(), e);

                    } catch (Throwable t) {
                        logger.error("Error running builder: " + builderMeta.getName());
                        for (DataBuilderExecutionListener listener : dataBuilderExecutionListener) {
                            try {
                                listener.afterException(dataFlowInstance, builderMeta, dataDelta, responseData, t);

                            } catch (Throwable error) {
                                logger.error("Error running post-execution listener: ", error);
                            }
                        }
                        Map<String, Object> objectMap = new HashMap<String, Object>();
                        objectMap.put("MESSAGE", t.getMessage());
                        throw new DataFrameworkException(DataFrameworkException.ErrorCode.BUILDER_EXECUTION_ERROR,
                                "Error running builder: " + builderMeta.getName() + t.getMessage(), objectMap, t);
                    }
                }
            }
            if(newlyGeneratedData.contains(dataFlow.getTargetData())) {
                logger.info("Finished running this instance of the flow. Exiting.");
                break;
            }
            if(newlyGeneratedData.isEmpty()) {
                logger.info("Nothing happened in this loop, exiting..");
                break;
            }
            StringBuilder stringBuilder = new StringBuilder();
            for(String data : newlyGeneratedData) {
                stringBuilder.append(data + ", ");
            }
            //logger.info("Newly generated: " + stringBuilder);
            activeDataSet.clear();
            activeDataSet.addAll(newlyGeneratedData);
            newlyGeneratedData.clear();
        }
        DataSet finalDataSet = dataSetAccessor.copy(dataFlowInstance.getDataFlow().getTransients());
        dataFlowInstance.setDataSet(finalDataSet);
        return new DataExecutionResponse(responseData);
    }

    /**
     * A instance of {@link com.flipkart.cp.convert.europa.databuilderframework.engine.DataBuilderExecutionListener}
     * that will be sent events when a builder is executed. This can be called multiple times with different listeners.
     * They will be called in order.
     *
     * @param listener Register a listener to be invoked during execution.
     */
    public void registerExecutionListener(DataBuilderExecutionListener listener) {
        dataBuilderExecutionListener.add(listener);
    }
}
