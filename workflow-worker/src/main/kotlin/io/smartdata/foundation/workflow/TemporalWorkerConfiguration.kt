package io.smartdata.foundation.workflow

import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import io.temporal.worker.WorkerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@ConditionalOnProperty(prefix = "smartdata.temporal", name = ["enabled"], havingValue = "true")
class TemporalWorkerConfiguration {
    @Bean(destroyMethod = "shutdown")
    fun temporalService(
        @Value("\${smartdata.temporal.target:127.0.0.1:7233}") target: String,
    ): WorkflowServiceStubs = WorkflowServiceStubs.newServiceStubs(
        WorkflowServiceStubsOptions.newBuilder().setTarget(target).build(),
    )

    @Bean
    fun temporalClient(
        service: WorkflowServiceStubs,
        @Value("\${smartdata.temporal.namespace:default}") namespace: String,
    ): WorkflowClient = WorkflowClient.newInstance(
        service,
        WorkflowClientOptions.newBuilder().setNamespace(namespace).build(),
    )

    @Bean
    fun temporalWorkerRuntime(
        client: WorkflowClient,
        activities: DeterministicFulfillmentActivities,
        @Value("\${smartdata.temporal.task-queue:smartdata-fulfillment-v1}") taskQueue: String,
    ): TemporalWorkerRuntime {
        val factory = WorkerFactory.newInstance(client)
        val worker = factory.newWorker(taskQueue)
        worker.registerWorkflowImplementationTypes(OrderFulfillmentWorkflowImpl::class.java)
        worker.registerActivitiesImplementations(activities)
        factory.start()
        return TemporalWorkerRuntime(factory)
    }
}

class TemporalWorkerRuntime(private val factory: WorkerFactory) : DisposableBean {
    override fun destroy() {
        factory.shutdown()
    }
}
