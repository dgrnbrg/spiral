This guide explains more about how to port Ring middleware to be Async


# Using unported Ring middleware with Async Ring

## Simple Case

If you're lucky, then this middleware's effect is strictly before OR after
the inner handler.

### Middleware modifies request

If the middleware only modifies the request before passing
it to the inner handler, and returns the inner handler's result
unmodified, then you've got the easiest case! You can use
`async-ring.core/sync->async-preprocess-middleware` to do this.
For example, if you wanted to port `wrap-file`, you could write:

```clojure
(-> async-ring-app
    (sync->async-preprocess-middleware wrap-file {:buffer 5}))
```

### Middleware modifies response

If the middleware only modifies the response, and doesn't ever
modify or *read* the request, then you can use this technique.
Remember, if your response modifier function takes the request
as an argument, this function won't work. Let's look at `wrap-file-info`
for example:

```clojure
(-> async-ring-app
  (sync->async-postprocess-middleware wrap-file-info {:buffer 5}))
```

## Hard Care

### Don't port

So you don't want to port your middleware over. It's still very
easy to port this middleware; however, it comes at a cost: the parallelism
is the maximum number of concurrent requests at this middleware AND
all subsequent middleware. Furthermore, the parallelism has the
potential to create too many threads! (although there are still easy
optimizations to implement that will help this).

Let's look at how we can use the `wrap-params` middleware, supporting 100
concurrent connections (and using 100 threads):

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
                                           {}))
      (async-ring.core/sync->async-middleware wrap-session
                                              {:parallelism 100}))

(def server (http-kit/run-server (async-ring.adapters.to-httpkit async-ring-app)
                                 {:port 8080}))
```

### Port

Now is where I say that actually you want to make this middlewares
feel first-class when you invoke them. For this, in `async-ring.middleware`,
I have a macro, `provide-process-middleware`, that can do all of these
types of ports in only a few charactors and maintain docstrings. If you
want to get a full performance native port, then look at the port of
`ring.middleware.session` to
`(provide-process-middleware session/wrap-session :both ...)`. I think that's
the easiest port to understand of the full ports.
