# Changelog

## 0.2.3

- Follows the send interval and the list cache set in the Rivium console: events are sent as often as the console says, and the experiment list is asked for again once it is older than the console allows. Changes apply while the app runs.
- A `flushInterval` other than the default (30 seconds) is your own choice and still wins over the console's value.
- Sends each event's own time.
- Events of a user the service keeps out of an experiment are sent like any other; the service decides what counts.

## 0.2.2

- Waits for a user token when the app starts before sign-in: `getVariant` returns your default and events are kept until a token arrives. New `isAwaitingUserToken`, `onAwaitingUserToken`, `setUserToken()`, `refreshUserToken()` and `clearUserId()`; `tokenProvider` may return `null`.
- `getVariant` no longer blocks the calling thread; use `getVariantAsync` to wait for the assignment.
- No more `NotInitialized` exceptions before `init` or after a failed first load.
- Sign-out and user switch clear the previous user's variants, attributes and pending events.
- The event queue holds at most 1,000 events, sends them in batches and backs off when the service is busy.

## 0.2.0

- Signed user tokens: new `tokenProvider` option. Your server mints a Rivium user token and the SDK sends it with every request. Required for assigning variants, tracking and flag evaluation.
- `setUserId` with a different user now clears the previous user's variants and token.
- Debug logs no longer include the API key or request bodies.
- Fixed server-side assignment and event sync.
