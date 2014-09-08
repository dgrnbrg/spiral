[![Build Status](https://travis-ci.org/dgrnbrg/async-ring.svg?branch=master)](https://travis-ci.org/dgrnbrg/async-ring)

# async-ring

Like Ring, but async.

To use in your Leiningen project, add the following

```clojure
;; For http-kit support
[async-ring "0.1.0"]
[http-kit "2.1.19"]

;; For jetty support
[async-ring "0.1.0"]
[ring/ring-jetty-adapter "1.3.1"]
```

## Motivation

Ring is a great foundation for building HTTP servers in Clojure. However, Ring
fails to solve many problems that high-performance and transactional HTTP
servers must solve:

- What does the server do when it can't handle the request rate?
- How can the server dedicate more or less resources to different requests?
- How can long-running HTTP requests be easily developed, without blocking threads?

Async Ring attempts to solve these problems by introducing a core.async based API
that is backwards compatible with Ring and popular Ring servers, so that you don't
need to rewrite your app to take advantage of these techniques.

## Features

### Forwards and Backwards compatible with Ring

Async Ring is 100% compatible with normal Ring. Want to use your Ring handler
in an Async Ring app? Just use `sync->async-hander`. What about mounting an
Async Ring handler into a normal Ring add? `async->sync-handler`. Maybe you'd
like to use the huge body of existing Ring middleware in your Async Ring app:
try `sync->async-middleware`. Or maybe you'd like to use async middleware with
your synchronous Ring app: just use `async->sync-middleware`.

### Beauty

Beauty is a simple concurrent router that lets you reuse your existing Ring routes
and handlers, be they Clout or Compojure, Hiccup or Selmer. You just need to pass
your existing app to the `beauty-router` middleware, and then annotate any handlers
that you want to run concurrently with prioritization.

### Integration with standard servers

Async Ring comes with adapters for Jetty 7 and http-kit, so that you don't even
need to change your server code. Just use `to-jetty` or `to-httpkit` to mount
an async handler onto your existing routing hierarchy.

### Core.async based API

Async Ring uses a standard pattern in core.async, in which the response channel
is tied to the request map. This way, it's easy to write sophisticated pipelines
that route and process the request according to whatever rules are necessary for
the job.

## Usage

### Getting Started

Let's first take a look at how to write "Hello World" in Async Ring with Http-Kit:

```clojure
(require '[org.http-kit.server :as http-kit])
(require 'async-ring.adapters.http-kit)
(require 'async-ring.core)

(def async-ring-app
  (async-ring.core/constant-response
    {:body "all ok" :status 200 :headers {"Content-Type" "text/plain"}}))

(def server (http-kit/run-server (async-ring.adapters.http-kit/to-httpkit async-ring-app)
                                 {:port 8080}))
```

In this example, we see how to use the `constant-response` handler, which is
the simplest Async Ring handler available. It always returns the same response.

After we create the app, we use `to-httpkit` to make it Http-Kit compatible,
and then we pass it to the Http-Kit server to start the application.

### Running traditional Ring apps on Async Ring

Now, we'll look at how we can run an existing traditional Ring app on
Async Ring with Jetty.

```clojure
(require '[compojure.core :refer (defroutes GET)])
(require '[async-ring.adapters.jetty :as jetty])
(require 'async-ring.core)

(defroutes traditional-ring-app
  (GET "/" []
    {:body "all ok" :status 200 :headers {"Content-Type" "text/plain"}}))

(def async-ring-app
  (async-ring.core/sync->async-adapter traditional-ring-app
                                       {:parallelism 10
                                        :buffer-size 5}))

(def server (jetty/run-jetty-async (jetty/to-jetty async-ring-app)
                                   {:port 8080
                                    :join? false}))
```

Here, we first create a traditional Ring app. Then, we add an adapter to make it
asynchronous, allowing up to 10 requests to be simultaneously routed and processed,
and up to 5 requests to be buffered. Finally, we start the Async Ring app on Jetty.

### Using traditional Ring middleware with Async Ring

Let's look at how we can run existing Ring middleware on an Async Ring stack.
We'll do this by creating a traditional Ring app that needs the `wrap-params`
middleware, making that Ring app async, and then adding `wrap-params` as an
async middleware.

```clojure
(require '[compojure.core :refer (defroutes GET)])
(require '[ring.middleware.params :refer (wrap-params)])
(require '[org.http-kit.server :as http-kit])
(require 'async-ring.adapters.http-kit)
(require 'async-ring.core)

(defroutes traditional-ring-app
  (GET "/" [q]
    {:body (str "got " q) :status 200 :headers {"Content-Type" "text/plain"}}))

(def async-ring-app
  (-> (async-ring.core/sync->async-adapter traditional-ring-app
                                           {:parallelism 10
                                            :buffer-size 5}))
      (async-ring.core/sync->async-middleware wrap-params
                                              {:parallelism 2
                                               :buffer-size 100}))

(def server (http-kit/run-server (async-ring.adapters.to-httpkit async-ring-app)
                                 {:port 8080}))
```

Here, we can see a few things. First of all, it's easy to compose Async Ring
handlers using `->`, just like regular Ring. Secondly, we can see that it's
possible to control the buffering and parallelism at each stage in the async
pipeline--this allows you to make decisions such as devoting extra CPU cores
to encoding/decoding middleware, and limiting the concurrent number of requests
to a database-backed session store.

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
(require 'async-ring.adapters.http-kit)
(require 'async-ring.core)

(defroutes beautified-traditional-ring-app
  (GET "/" []
    (beauty-route :main (handle-root)))
  (GET "/health" []
    (handle-health-check))
  (ANY "/rest/endpoint" []
    (beauty-route :endpoint (handle-endpoint)))
  (ANY "/rest/endpoint/:id" [id]
    (beauty-route :endpoint 8 (handle-endpoint-id id))))

(def server (http-kit/run-server (async-ring.adapters.to-httpkit
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

## License

Copyright © 2014 David Greenberg

Distributed under the Eclipse Public License either version 1.0
