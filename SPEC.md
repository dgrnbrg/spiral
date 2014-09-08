### Async Ring Spec

Async ring allows you to use core.async to build more sophisticated HTTP servers in Clojure. It is designed to allow the implementation of custom quality-of-service middleware, improved multi-core utilization, and graceful work-shedding.

Async ring is designed to be compatible with existing standard ring handlers and middleware.

Async ring is defined in terms of handlers, middleware, adapters, request maps, and response maps, as defined below. Async ring's request map and response map are identical to 
[Ring](https://github.com/ring-clojure/ring/blob/1.3/SPEC), except for the exceptions below.

## Handlers

Async ring handlers constitute the core logic of the web application. Handlers are implemented as channels with at least one worker that will process requests that are placed on the channel. To process a request, the worker must put either a response map or an error onto the request's callback channels.

## Middleware

Async ring middleware augments the functionality of handlers by invoking them in the process of generating responses. Typically middleware will accept requests on its own channel, and decide whether to delegate to one or more other handlers. Most middleware takes the sub-handler's channel as the first argument, and other options as subsequent arguments.

Most handlers and middleware take at least two options: `:parallelism` and `:buffer-size`. These options control how many tasks the middleware/handler will compute simultaneously, and how many tasks will be buffered before backpressure will slow down upstream requests.

## Adapters

Async ring adapters connect async-ring handlers and middleware to existing HTTP servers. The adapter is implemented as a function that takes 2 arguments: the initial handler/middleware's request channel, and an options map. Each adapter integrates with its host server differently, in order to expose as many other features of the underlying host platform as possible.

## Request Map

Async ring request maps are the same as regular ring request maps, with the following extra keys:

- `:async-response`: (Required, core.async channel) When a handler or middleware is done processing a request successfully, the response map should be put onto this channel. Only one response map should be put onto each `:async-response` channel.

- `:async-error`: (Required, core.async channel) When a handler or middleware is done processing a request and there was an error, the exception should be put onto this channel. Only one exception should be put onto each `:async-error` channel.

## Response Map

Aysnc ring response maps are exactly the same as regular ring response maps.
