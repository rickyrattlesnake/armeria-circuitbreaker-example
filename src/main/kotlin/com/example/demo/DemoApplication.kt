package com.example.demo

import com.linecorp.armeria.client.circuitbreaker.FailFastException
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.MessageSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.coRouter
import org.springframework.web.reactive.function.server.json
import org.springframework.web.reactive.function.server.queryParamOrNull
import java.util.Locale

@SpringBootApplication
class DemoApplication

fun main(args: Array<String>) {
	runApplication<DemoApplication>(*args)
}


@Configuration
class Routers(private val handler: Handler) {
	@Bean
	fun coVerticalsRouter() = coRouter {
		(accept(MediaType.APPLICATION_JSON) and "/").nest {
			"/".nest {
				GET("/example_endpoint", handler::exampleHandlerMethod)
			}
		}
	}
}

@Component
class Handler(private val webclientBuilder: WebClient.Builder) {
	suspend fun exampleHandlerMethod(request: ServerRequest): ServerResponse {
		return ServerResponse.ok().json()
			.bodyValueAndAwait(
				makeExternalRequest()
			)
	}

	suspend fun makeExternalRequest(): String {
		val client = webclientBuilder.baseUrl("https://run.mocky.io/v3").build()

		return try {
			client.get()
				.uri { builder -> builder.path("/af9acdc9-05f8-4cd9-bf1f-c1313a1a8313/").build() }
				.retrieve()
				.awaitBody<String>()
		} catch (failFastException: FailFastException) {
			println("[!!!] failFastException => $failFastException")
			throw failFastException
		} catch (error: Throwable) {
			println("[!!!] ERROR => $error")
			throw error
		}
	}
}

