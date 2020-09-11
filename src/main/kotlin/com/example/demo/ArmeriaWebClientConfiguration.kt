package com.example.demo

import com.linecorp.armeria.client.ClientFactory
import com.linecorp.armeria.client.ClientOptions
import com.linecorp.armeria.client.circuitbreaker.CircuitBreaker
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerBuilder
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerClient
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRuleWithContent
import com.linecorp.armeria.client.circuitbreaker.FailFastException
import com.linecorp.armeria.client.proxy.ProxyConfig
import com.linecorp.armeria.client.retry.Backoff
import com.linecorp.armeria.client.retry.RetryRule
import com.linecorp.armeria.client.retry.RetryingClient
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.grpc.GrpcWebTrailers
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames
import com.linecorp.armeria.spring.web.reactive.ArmeriaClientConfigurator
import io.grpc.Status.Code
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.InetSocketAddress
import java.util.function.BiFunction

@Configuration
class ArmeriaWebClientConfiguration() {
    private val servicePartialFailureGrpcStatusCodes = setOf(
        Code.UNIMPLEMENTED.value(),
        Code.DEADLINE_EXCEEDED.value(),
        Code.RESOURCE_EXHAUSTED.value(),
        Code.FAILED_PRECONDITION.value(),
        Code.UNAVAILABLE.value(),
        Code.ABORTED.value(),
        Code.INTERNAL.value()
    )

    @Bean
    fun provideArmeriaClientOptions(): ClientOptions {
        return ClientOptions.builder()
            .factory(
                ClientFactory.builder()
                    .tlsNoVerify()
                    .proxyConfig(ProxyConfig.socks4(InetSocketAddress("localhost", 8889)))
                    .build()
            )
             .decorator(
                 CircuitBreakerClient.newPerHostAndMethodDecorator(
                    BiFunction { host: String, method: String ->
                        CircuitBreaker.builder("example-cb::$host::$method")
                         .minimumRequestThreshold(3)
                         .build()
                    },
                    buildCircuitBreakerPolicy()
                 )
             )
            .decorator(
                RetryingClient.newDecorator(
                    buildRetryPolicy(),
                    1
                )
            )
            .build()
    }

    @Bean
    fun provideArmeriaClientConfigurator(clientOptions: ClientOptions): ArmeriaClientConfigurator =
        ArmeriaClientConfigurator { builder -> builder.options(clientOptions) }

    private fun buildRetryPolicy(): RetryRule {
        val exponentialBackOff = Backoff.exponential(
                100,
                100
            ).withJitter(0.1)

        val doNotRetryOnCircuitBreakerFailures = RetryRule.builder()
            .onException(FailFastException::class.java)
            .thenNoRetry()
        val retryOnServerErrorsOnlyForHttpMethodsSafeToRetry = RetryRule.builder(HttpMethod.idempotentMethods())
            .onServerErrorStatus()
            .onException()
            .thenBackoff(exponentialBackOff)
        val retryOnUnprocessedServerRequestsForAllStandardHttpMethods = RetryRule.builder(HttpMethod.knownMethods())
            .onUnprocessed()
            .thenBackoff(exponentialBackOff)

        return RetryRule.of(
            doNotRetryOnCircuitBreakerFailures,
            retryOnServerErrorsOnlyForHttpMethodsSafeToRetry,
            retryOnUnprocessedServerRequestsForAllStandardHttpMethods
        )
    }

    private fun buildCircuitBreakerPolicy(): CircuitBreakerRuleWithContent<HttpResponse> {
        val countServerSideErrors = CircuitBreakerRuleWithContent.builder<HttpResponse>()
            .onServerErrorStatus()
            .thenFailure()

        val doNotCountErrorsWhenServerDidNotProcessRequest = CircuitBreakerRuleWithContent.builder<HttpResponse>()
            .onUnprocessed()
            .thenIgnore()

        val countOtherExceptions = CircuitBreakerRuleWithContent.builder<HttpResponse>()
            .onException()
            .thenFailure()

        val countGrpcServicePartialFailures =
            CircuitBreakerRuleWithContent.builder<HttpResponse>()
                .onResponse { ctx, response ->
                    response.aggregate().thenApply {
                        val grpcStatus = GrpcWebTrailers.get(ctx)?.getInt(GrpcHeaderNames.GRPC_STATUS)
                        grpcStatus != null && servicePartialFailureGrpcStatusCodes.contains(grpcStatus)
                    }
                }
                .thenFailure()

        return CircuitBreakerRuleWithContent.of(
            countServerSideErrors,
            doNotCountErrorsWhenServerDidNotProcessRequest,
            countOtherExceptions,
            countGrpcServicePartialFailures
        )
    }
}
