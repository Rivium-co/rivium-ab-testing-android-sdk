# Changelog

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
