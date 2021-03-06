[![Build Status](https://travis-ci.org/dgrnbrg/spiral.svg?branch=master)](https://travis-ci.org/dgrnbrg/spiral)

# spiral

A Ring that doesn't block.

Spiral integrates Ring and core.async.

To use in your Leiningen project, add the following

```clojure
;; For http-kit support
[spiral "0.1.0"]
[http-kit "2.1.19"]

;; For Immutant support
[spiral "0.?.0"]
[org.immutant/web "2.0.0"]

;; For jetty support
[spiral "0.1.0"]
[ring/ring-jetty-adapter "1.3.1"]
```

## Motivation

Ring is a great foundation for building HTTP servers in Clojure. However, Ring
fails to solve many problems that high-performance and transactional HTTP
servers must solve:

- What does the server do when it can't handle the request rate?
- How can the server dedicate more or fewer resources to different requests?
- How can long-running HTTP requests be easily developed, without blocking threads?

Spiral attempts to solve these problems by introducing a core.async based API
that is backwards compatible with Ring and popular Ring servers, so that you don't
need to rewrite your app to take advantage of these techniques.

## Features

### Beauty

Note: Beauty is currently the primary reason to use this library.

Have you ever wanted to prioritize routes that serve static data over routes
that serve DB queries? What about reserving dedicated capacity for your
administrator accounts, to ensure responsiveness under heavy load? Maybe
you want to give dedicated capacity to your paid tier over free.

Beauty is a simple concurrent router that lets you reuse your existing Ring routes
and handlers, be they Clout or Compojure, Hiccup or Selmer. You just pass
your existing app to the `beauty-router` middleware, and then annotate any handlers
that you want to run concurrently with prioritization. There is an example
of how to do this further down in the README.

### core.async based

With Spiral, you can leverage the power of core.async in your Ring handlers.
Now, you can park on your database and REST requests using `<!` and `!>`! Soon,
there will be syntax sugar for making this trivial.

### Forwards and Backwards compatible with Ring

Spiral is 100% compatible with normal Ring. Want to use your Ring handler
in an Spiral app? Just use `sync->async-handler`. What about mounting an
Spiral handler into a normal Ring add? `async->sync-handler`. Maybe you'd
like to use the huge body of existing Ring middleware in your Spiral app:
try `sync->async-middleware`. Or maybe you'd like to use async middleware with
your synchronous Ring app: just use `async->sync-middleware`.

Spiral also includes a complete set of optimized ports of Ring middleware.
These ports include a ported test suite, so you can feel comfortable in the logic
being executed.

### Integration with standard servers

Spiral comes with adapters for Jetty 7, Immutant 2, and http-kit, so that you
don't even need to change your server code. Just use `to-jetty`, `to-immutant`, or
`to-httpkit` to mount an async handler onto your existing routing hierarchy.

### Ports of many Ring middleware

`spiral.middleware` contains ports of all the middleware found in Ring core.
Just post an issue to get your favorite middleware ported!

## Usage

See `SPEC.md` for a specific treatment of how the format works.

### Getting Started

Let's first take a look at how to write "Hello World" in Spiral with http-kit:

```clojure
(require '[org.http-kit.server :as http-kit])
(require 'spiral.adapters.http-kit)
(require 'spiral.core)

(def spiral-app
  (spiral.core/constant-response
    {:body "all ok" :status 200 :headers {"Content-Type" "text/plain"}}))

(def server (http-kit/run-server (spiral.adapters.http-kit/to-httpkit spiral-app)
                                 {:port 8080}))
```

In this example, we see how to use the `constant-response` handler, which is
the simplest Spiral handler available. It always returns the same response.

After we create the app, we use `to-httpkit` to make it http-kit compatible,
and then we pass it to the http-kit server to start the application.

### Running traditional Ring apps on Spiral

Now, we'll look at how we can run an existing traditional Ring app on
Spiral with Jetty.

```clojure
(require '[compojure.core :refer (defroutes GET)])
(require '[spiral.adapters.jetty :as jetty])
(require 'spiral.core)

(defroutes traditional-ring-app
  (GET "/" []
    {:body "all ok" :status 200 :headers {"Content-Type" "text/plain"}}))

(def spiral-app
  (spiral.core/sync->async-adapter traditional-ring-app
                                       {:parallelism 10
                                        :buffer-size 5}))

(def server (jetty/run-jetty-async (jetty/to-jetty spiral-app)
                                   {:port 8080
                                    :join? false}))
```

Here, we first create a traditional Ring app. Then, we add an adapter to make it
asynchronous, allowing up to 10 requests to be simultaneously routed and processed,
and up to 5 requests to be buffered. Finally, we start the Spiral app on Jetty.

And here is the same app again, but using Immutant:

```clojure
(require '[compojure.core :refer (defroutes GET)])
(require 'immutant.web)
(require 'spiral.adapters.immutant)
(require 'spiral.core)

(defroutes traditional-ring-app
  (GET "/" []
    {:body "all ok" :status 200 :headers {"Content-Type" "text/plain"}}))

(def spiral-app
  (spiral.core/sync->async-adapter traditional-ring-app
                                       {:parallelism 10
                                        :buffer-size 5}))

(def server (immutant.web/run (spiral.adapters.immutant/to-immutant spiral-app)
                              :port 8080))
```

### Using Ring middleware

Spiral has a small but growing library of native ports of Ring middleware. By using
a native port of the Ring middleware, you're able to get the best performance.

```clojure
(require '[compojure.core :refer (defroutes GET)])
(require '[spiral.middleware :refer (wrap-params)])
(require '[org.http-kit.server :as http-kit])
(require 'spiral.adapters.http-kit)
(require 'spiral.core)

(defroutes traditional-ring-app
  (GET "/" [q]
    {:body (str "got " q) :status 200 :headers {"Content-Type" "text/plain"}}))

(def spiral-app
  (-> (spiral.core/sync->async-adapter traditional-ring-app
                                           {:parallelism 5
                                            :buffer-size 5}))
      (wrap-params {:parallelism 10
                    :buffer-size 100}))

(def server (http-kit/run-server (spiral.adapters.to-httpkit spiral-app)
                                 {:port 8080}))
```

Here, we can see a few things. First of all, it's easy to compose Spiral
handlers using `->`, just like regular Ring. Secondly, we can see that it's
possible to control the buffering and parallelism at each stage in the async
pipeline--this allows you to make decisions such as devoting extra CPU cores
to encoding/decoding middleware, and limiting the concurrent number of requests
to a database-backed session store.

Ported middleware lives in `spiral.middleware`. The
second argument to the async-ported middleware is always the async options, such
as parallelism and the buffer size.

If you'd like to see your middlewares ported to Spiral, just file an issue
and I'll do that quickly.

### Using Beauty

Beauty is a concurrent routing API that adds quality of server (QoS) features to Ring.
Quality of service allows you separate routes that access independent databases to
ensure that slowness in one backend doesn't slow down other requests. QoS also allows
you to dynamically decide to prioritize some requests over others, to ensure that
high-priority requests are completed first, regardless of arrival order.

Let's first look at a simple example of using Beauty:

```clojure
(require '[compojure.core :refer (defroutes GET ANY)])
(require '[org.http-kit.server :as http-kit])
(require 'spiral.adapters.http-kit)
(require 'spiral.core)

(defroutes beautified-traditional-ring-app
  (GET "/" []
    (beauty-route :main (handle-root)))
  (GET "/health" []
    (handle-health-check))
  (ANY "/rest/endpoint" []
    (beauty-route :endpoint (handle-endpoint)))
  (ANY "/rest/endpoint/:id" [id]
    (beauty-route :endpoint 8 (handle-endpoint-id id))))

(def server (http-kit/run-server (spiral.adapters.to-httpkit
                                   (beauty-router
                                     beautified-traditional-ring-app
                                     {:main {:parallelism 1}
                                      :endpoint {:parallelism 5
                                                 :buffer-size 100}}))
                                   {:port 8080}))
```

The first thing we added is the `beauty-route` annotations to each route that
we want to run on a prioritized concurrent pool. Note that you can choose to
freely mix which routes are Beauty routes, and which routes are executed
single-threaded (`/health` isn't executed on a Beauty pool).

Next, notice that `beauty-route` takes an argument: the name of the pool that you want to execute
the request on. `beauty-router` handles creating pools with bounded concurrency
and a bounded buffer of pending requests. In this example, we are using 2 pools:
`:main`, which has a single worker and only services requests to `/`, and `:endpoint`,
which services all of the routes under `/rest`. The `:endpoint` pool has 5
concurrent workers, and it can queue up to 100 requests before it exhibits
backpressure.

Finally, notice that the final route (`/rest/endpoint/:id`) uses the priority form of
`beauty-route`: normally, all requests are handled at the standard priority, `5`.
In some cases, you may know that certain routes are usually faster to execute,
or that certain clients may have have a cookie that indicates they need better
service. In that case, you can specify the priority for a `beauty-route`d task.
These priorities are used to determine which request will be handled from the
pool's buffer.

The Beauty Router should be flexible enough to solve most QoS problems; nevertheless, Pull Requests are welcome to improve the functionality!

## Implementation

What follows is a brief note on the fundamental implementation of Spiral.

In Spiral, an async handler is simply a channel, the fundamental composable
unit of core async. Ring request maps are simply passed into that channel. Clearly,
it's easy to flow the data through the handlers, but how can we flow it back
out to the client? Each request contains 2 extra keys, `:async-response` and
`:async-error`: if you'd like to send a response, put it onto the channel
`:async-response`. If you'd like to send an error, put it onto the channel
`:async-error`. Middleware can intercept responses by inserting their own
channels into those keys.

## Comparison with Pedestal

At first glance, Spiral and Pedestal seem very similar--they're both frameworks
for building asynchronous HTTP applications using a slightly modified Ring API. In
this section, we'll look at some of the differences between Pedestal and Spiral.

1. Concurrency mechanism: in Pedestal, you write pure functions for each request lifecycle state transition, and the Pedestal server schedules these function for you. In
Spiral, you write core.async code, so the control flow of your handler is exactly
how you write it. In other words, you can using `<!` and `>!` to block wherever you
want.
1. Performance: Pedestal and Spiral both allow for many more connections than
threads, thus enabling many more concurrenct connections that Ring.
1. Composition: in Pedestal, interceptors are placed in a queue for executor. This
allows for interceptions to know the entire queue of execution as it stands, at
the expense of always encoding the request processing as a queue. In Spiral,
handlers are only identified by their input channel. Thus, Spiral handlers cannot
automatically know what other executors are in the execution pipeline. On the other
hand, Spiral handlers allow for async middleware to be written using `<!` and `>!`
when delegating to a subhandler, rather than needing to explicitly implement
the state machine.
1. Chaining behavior: in Pedestal, the interceptor framework handles chaining
behavior, which allows for greater programmatic insight and control. In Spiral,
function composition handles chaining behavior, just like in regular Ring.
1. Compatibility with Ring: in Pedestal, you must either port your Ring middlewares, or deal with the fact that they cannot be paused or migrate threads. In Spiral, all existing Ring middleware is supported; however, you will get better performance by porting middlewares.

## Performance

Performance numbers are currently only preliminary.

I benchmarked returning the "constant" body via Spiral, Traditional Ring, and using a callback. All tests were done on http-kit.

|            | Traditional Ring | httpkit async | Spiral |
|------------|------------------|---------------|------------|
| Mean       | 1.45 ms          | 1.542 ms      | 1.953 ms   |
| 90th %ile  | 2 ms             | 2 ms          | 2 ms       |

Thus Spiral adds 500 microseconds to each call, but doesn't impact
outliers significantly. This is something
I'd like to try to to improve; however, the latency cost is worth it
if you need these other concurrency features.

## License

Copyright © 2014 David Greenberg

Distributed under the Eclipse Public License either version 1.0
