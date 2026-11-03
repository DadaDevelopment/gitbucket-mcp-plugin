# Authentication

The MCP endpoint accepts two authentication methods. Both resolve to a real
GitBucket account, and neither grants anything that account does not already
have.

| Method | For | State |
| --- | --- | --- |
| HTTP Basic | clients that can send a username and password or personal access token, such as Claude Desktop | supported |
| OAuth 2.1 Bearer | clients that require a browser authorization flow, such as ChatGPT | this document |

## Why the plugin is also the authorization server

GitBucket is **not** an OAuth authorization server. It ships
`AccessToken`/`AccessTokenService` — personal access tokens, stored as a hash —
and session authenticators, but no authorization endpoint, no token endpoint,
no client registry and no key set. Its OAuth support is as a *client*, for
signing users in from an external provider. Verified against the published
artifacts rather than assumed.

So an authorization server has to come from somewhere, and there are two
honest options.

**An external authorization server** (Keycloak, Authentik, a hosted IdP). This
can be done safely: the mapping from the external subject to a GitBucket
account is an explicit, administrator-established binding on
`(issuer, subject)`, stored and verified, and an identity provider that
GitBucket already trusts for sign-in is a reasonable thing to trust for
authorization too. Matching on email is not the only option and is not what
makes this approach hard.

What makes it hard is operational. It is a second service to deploy, operate,
upgrade and back up; it needs its own availability story, because the MCP
endpoint stops authenticating when it is down; and the binding table has to be
populated and maintained by hand for every user, which is work an
administrator does before anyone can connect. For a plugin whose premise is
"no separate service or proxy required", that cost falls on every deployment
to serve a case most of them do not have. Rejected on those grounds, and
recorded here so the choice is visible rather than implied. A deployment that
already runs an IdP and wants it to own MCP authorization is a reasonable
future option, not a contradiction of this one.

**The plugin acts as the authorization server**, authenticating the resource
owner with the GitBucket session the user already has. No new identity store,
no account linking, no second service. The resource owner is a GitBucket
account by construction, because the only way to reach the consent screen is
to be signed in to GitBucket. Chosen.

The cost of that choice is honest too: this plugin then owns authorization
codes, tokens and consent records, and owns their security properties. The
rest of this document is about those properties.

## What is a dependency and what is ours

The protocol types come from **`com.nimbusds:oauth2-oidc-sdk`**, Apache-2.0,
which GitBucket already carries on its runtime classpath for its own OpenID
Connect sign-in. It is therefore a `compileOnly` dependency and is **not**
bundled into the plugin JAR: no duplicate classes, no version skew with the
host, no growth in the artifact. Pinned at 11.33, the version shipped by the
oldest supported GitBucket, so only API present across the whole tested range
is used.

| Supplied by the library | Implemented here |
| --- | --- |
| PKCE: verifier syntax, S256 computation (`CodeVerifier`, `CodeChallenge`) | the policy that S256 is the only accepted method |
| RFC 8414 authorization server metadata: field names, JSON shape | the values in it, and the claim that each one is true |
| Authorization and token request parsing, error objects, token responses, revocation requests | which requests are honoured, and every decision about whether to honour one |
| `ClientID`, `Scope`, `State`, `AuthorizationCode`, `BearerAccessToken` types | the storage, lifetime, binding and rotation of everything they name |
| — | RFC 9728 protected resource metadata, which has no type in the shipped SDK version |
| — | the `WWW-Authenticate` challenge |
| — | consent, CSRF, GitBucket account resolution, scope enforcement, the CIMD fetcher and its SSRF controls, and the schema |

Randomness and hashing are the JDK's `SecureRandom` and `MessageDigest`. No
cryptography is implemented here, and nothing in the table's right-hand column
is described anywhere in this repository as coming from the library.

## Boundaries

| Role | Where it lives |
| --- | --- |
| MCP resource server | the existing `/plugin-mcp/` endpoint, now also accepting `Authorization: Bearer` |
| Authorization server | this plugin, at its own endpoints |
| Resource owner authentication | GitBucket's own session — the plugin never sees a password |
| Account resolution | the signed-in GitBucket account, carried on the grant |
| Repository authorization | unchanged: GitBucket's `hasGuestRole` / `hasDeveloperRole`, per request |
| Persistent state | plugin-owned tables, created by a Solidbase migration |

Effective access is the **intersection** of the granted scope and the account's
current GitBucket permissions. A token is never sufficient on its own: every
tool still runs the same repository guard it runs under Basic authentication,
so a grant issued yesterday cannot outlive a role removed today.

## Registration: client ID metadata documents

ChatGPT supports three registration mechanisms. This plugin implements one.

- **CIMD** — the client's `client_id` is an HTTPS URL serving its own metadata.
  Nothing is registered and nothing is stored. Implemented.
- **Dynamic client registration** (RFC 7591) — requires an open registration
  endpoint and a client table. Not implemented: it is a larger attack surface
  for a self-hosted server, and CIMD covers the client this work targets.
- **Pre-registration** — an administrator pastes a client ID and secret.
  Not implemented.

Fetching a metadata document means the server makes an outbound request to a
URL a caller supplied, which is a server-side request forgery primitive if it
is done carelessly. The fetcher is therefore bounded: HTTPS only, a response
size and time limit, redirects refused outright rather than followed, and
private, loopback, link-local, unique-local, carrier-grade and multicast
address ranges refused after DNS resolution — every resolved address, not the
first. An IPv4 address written in IPv6 notation is unwrapped and judged as the
address it is.

**The request goes to the address that was checked.** This is the part that is
easy to get wrong, and this fetcher did: checking an address and then handing a
host *name* to a URL connection checks nothing, because the connection
resolves the name again, independently, and that second answer decides where
the request goes. Verifying afterwards is later still, since a response only
exists once the request has been sent — and sending it is the whole of the
injury. Neither `HttpURLConnection` nor `java.net.http.HttpClient` can be told
which address to use. Apache HttpClient can: its `DnsResolver` is consulted
for the route, so answering with the one address that passed the check is what
pins the destination.

Pinning the address gives up nothing about authenticating the peer. The socket
factory is the JVM's default HTTPS factory with the default hostname verifier,
so the usual certificate name check happens against the name; SNI carries the
name too. No proxy is consulted, because a proxied request goes wherever the
proxy decides and destination control would be an illusion.

**A document that arrives incompletely is not a document.** The HTTP message
itself was parsed here once, and the parser was wrong in the two ways a
hand-written one is prone to: a body that stopped short of its
`Content-Length` was returned as though it had finished, and a chunk size that
did not parse was treated as the terminating zero chunk. Both turned a
truncated prefix into a valid client registration, because a JSON document
that parses says nothing about whether the message carrying it completed
(RFC 9112 §6.3, §7.1, §8). Message parsing is now Apache HttpClient's, which
raises on both — and it is already on GitBucket's runtime classpath, so it is
`compileOnly` and nothing is bundled. The size limit refuses an oversized
document rather than returning a prefix of it, for the same reason.

Two bounds need stating explicitly, because moving to a maintained stack lost
them both and neither loss is visible from a response the server has already
finished sending. A body past the cap **aborts the request** rather than
closing the entity stream: closing a half-read entity makes the client consume
the rest of the framed body first, so a peer that keeps supplying a tail would
hold the fetch open exactly as long as it liked. And the connection manager's
default message constraints are unlimited, so header line length and header
count are set explicitly — 8 KiB and 100, which together bound the whole
header block.

Redirects are refused rather than re-validated because an allowed host
bouncing to a forbidden one is the usual way such a fence is bypassed, and
there is no legitimate reason for a client's own metadata document to move.

One escape exists, off by default. `gitbucket.mcp.oauth.allowLoopbackClients`
permits a `client_id` on a loopback address, and opens **loopback only**: the
private, link-local, carrier-grade and multicast ranges stay forbidden with it
on. It exists because a developer running an MCP client on the same machine as
GitBucket otherwise cannot complete an authorization at all. A deployment that
never sets it behaves exactly as if it did not exist.

## Tokens

Access tokens are opaque, generated from a cryptographic random source and
stored as a SHA-256 hash, in the same spirit as GitBucket's own personal
access tokens. They are not self-contained.

That is a deliberate trade. A signed self-contained token needs no storage and
no lookup, but it also cannot be revoked before it expires — revocation
latency becomes the token lifetime. An opaque token costs one indexed lookup
per request and gives **immediate** revocation, which is the property that
matters when a grant is withdrawn or an account is disabled.

| Credential | Lifetime | Revocation |
| --- | --- | --- |
| authorization code | 60 seconds, single use | consumed atomically |
| access token | 1 hour | immediate |
| refresh token | 30 days, rotated on use | immediate; reuse of a rotated token revokes the whole grant |

Reuse detection applies to races as well as to theft, because the server
cannot tell them apart. A correctly bound refresh token presented a second
time is treated as reuse and the whole grant is revoked. Nothing about that
decision identifies the legitimate caller — the server has no way to do so,
which is precisely why reuse ends the family rather than being arbitrated.

The consequence is an interoperability requirement of this policy, and worth
stating plainly: **a client must serialise its refreshes.** If it presents the
same refresh token twice at once, one request rotates it and the other finds a
token that has already been rotated, the grant is revoked, and neither request
leaves the client with a credential it can keep using. That is what OAuth 2.1
asks for; it is a constraint on the client, not a claim about the server's
ability to tell two callers apart.

Three things are kept distinct, because they are often conflated:

- **The database transition.** One refresh credential cannot produce two
  independently valid successors, and the chain cannot fork. This holds under
  any scheduling and is asserted on stored state.
- **Replay handling.** A correctly bound second presentation revokes the
  grant, and that revocation is committed whatever the HTTP endpoint answers.
- **The HTTP result.** A response may already have completed before the
  revocation landed. It is not undone. The guarantee is stated where it holds:
  every *subsequent* authorization check refuses credentials from the revoked
  grant.

A **binding failure is not a replay.** Presenting a refresh token with the
wrong `client_id` is refused and changes nothing: it does not rotate the
token, does not revoke the grant, and the legitimate client's next refresh
still works. The client id is not a secret — anyone who learned a refresh
token could send it with any client id they liked, and treating that as
evidence of theft would hand an unauthenticated caller a way to destroy a
credential that is in legitimate use.

The same holds for the authorization code, and for a code that has **already
been spent**. The bindings are checked first, so a wrong client, redirect URI,
resource or PKCE verifier against a used code is refused on its own terms and
the grant it bought survives. Revoking on replay is defensible only for a
presentation that could have come from the legitimate client; whoever captured
a code but not the PKCE verifier must not be able to end the session with it.
A correctly bound replay still revokes.

Losing a race is treated as a replay, because it is one. When the conditional
claim on a code matches no row, the row is re-read: if something else took it,
the presentation was correctly bound and arrived second, which is the same
situation as an outright replay and gets the same policy.

Expiry is treated differently on purpose. An expired credential is refused but
does **not** revoke the grant: that is the client being slow, not a credential
being somewhere it should not be. This applies to a superseded refresh token
whose own expiry has passed as well — it is dead of old age, not evidence, and
treating it as a replay would let an old useless token destroy the successor
the legitimate client is using. Cleanup only removes credentials that are
already expired, so the unexpired superseded rows that replay detection needs
are never swept away.

### The audience is checked where the credential is used

A grant records the resource it was issued for. That is only a fact in a table
until it is compared with the resource this server actually is, and the
comparison belongs at the point of use: a token outlives the request that
created it, so a reconfigured deployment — or a neighbouring one sharing a
database — presents a perfectly valid, unexpired credential for somewhere
else. Every bearer request checks it before a tool is chosen, and refuses a
mismatch exactly like any other unusable token, so the refusal reveals
nothing. Rotation treats the audience as a binding alongside the client id,
both the resource this server is and the resource the client asked for, so a
mismatch consumes nothing and revokes nothing.

## Scopes

| Scope | Grants |
| --- | --- |
| `mcp:read` | the read-only tools |
| `mcp:write` | the mutating tools, in addition to `mcp:read` |

Every tool declares which one it needs, as an abstract member, so a tool that
forgets to declare does not compile rather than defaulting to the weaker
requirement. A read grant cannot mutate: the scope check happens before the
tool runs, and the repository guard still happens inside it.

**Who decides which scopes are asked for.** The client does, in its
authorization request. The consent screen lists exactly the scopes that
request named, and offers one decision over the set — Authorize or Cancel.
There is no per-scope selector, so a client asking for `mcp:write` is approved
or refused as a whole; a user who wants a read-only connection refuses and
the client asks for less. A request naming no scope at all is treated as
asking for the weaker one, `mcp:read`.

What is granted is the set the server recorded when the request arrived, held
in the user's own session, not the value posted back with the consent form.
That record is removed on any decision, allow or deny, so a resubmitted form
cannot mint a second code.

OIDC scopes (`openid`, `email`, `profile`) and a UserInfo endpoint are **not**
implemented, and the metadata does not advertise them. They are only needed
for workspace domain restrictions, and advertising a capability that is not
implemented is worse than not having it.

## What the metadata says is what exists

The discovery documents describe only implemented behaviour. In particular
`code_challenge_methods_supported` lists `S256` and nothing else, because the
plain method is not accepted; `grant_types_supported` lists only
`authorization_code` and `refresh_token`; and no `registration_endpoint` is
published, because dynamic registration is not implemented.

## Storage

The four tables — grant, authorization code, refresh token, access token —
are created by the plugin's own schema migration, which GitBucket runs at
startup through Solidbase. Nothing needs to be created by hand, and an
existing installation of an earlier version of this plugin picks the migration
up on the next restart.

Two notes for anyone changing that migration. Its root element is a bare
`<changeSet>`, not a Liquibase `<databaseChangeLog>`: Solidbase supplies the
wrapper, the change set id and the author, and a file carrying its own root
parses as a nested changelog and is **skipped silently** — the plugin still
loads and the first request that needs a table fails. And the version list
has to be declared on the root-package class named `Plugin`, which is the one
GitBucket loads by name; it extends `MCPPlugin` so there is a single
declaration. Both failures happened here, and both are now covered by tests
that run the real mechanism rather than a convenient substitute.

## What has been tested

Compiled, and the unit suite executed, against the runtime classpaths of
GitBucket **4.46.0**, **4.47.0** and **4.48.0**, each with the
`oauth2-oidc-sdk` version that release ships (11.33 for 4.46.0, 11.38.2 for
the other two). Those three are what has been run; versions between or beyond
them are untested rather than known-good.

The flow itself is exercised over real HTTP against a disposable GitBucket
with the plugin installed: discovery, consent, approval, denial and CSRF
rejection, code exchange and every binding failure, refresh rotation and
replay, revocation, Bearer authentication, and tool calls through the
production dispatcher — with the effects read back from GitBucket's own REST
API rather than from the plugin's replies. Those controls are
`itest/oauth_controls.py`.

Installation, upgrade and restart are a separate gate again, because a
migration can be written, committed, tested against a database directly and
still never run: what executes is what the loaded `Plugin` class declares and
what Solidbase finds packaged in the JAR. `itest/upgrade_gate.py` starts real
GitBucket processes and checks the operator's questions — the previous
plugin runs, the candidate replaces it, the migration executes, data created
under the previous plugin survives, a grant issued after the upgrade survives
a restart, and a revocation performed before a restart is still in force
after it. That last one is what distinguishes persistence from a cache.

That gate has been run on **both** supported backends: the bundled H2, and
PostgreSQL 16, from a blank database through the pre-OAuth plugin to this
one. On PostgreSQL the four tables and the recorded module version were read
back from the database afterwards. The processes run with a non-UTC default
time zone on purpose — a UTC host is the configuration that once hid a
timestamp defect here.

### A commercial client, and what it does not establish

A client's behaviour is not the protocol, so the HTTP controls above said
nothing about whether any particular product would accept this server. One has
since connected. ChatGPT's web connector completed the flow against a running
GitBucket with this plugin installed — discovery, client ID metadata
registration, GitBucket sign-in, consent — and was then used for ordinary
work over the resulting connection, by the account that consented, a site
administrator. The operations observed through it:

- `whoami`, resolving that account and returning the plugin's self-reported
  descriptor;
- repository, pull request, issue and review-thread reads;
- issue creation;
- adding an issue comment, reading it back, editing that same comment by
  appending to it, and reading it back byte for byte.

A second MCP client, not ChatGPT's and over a connection whose authentication
path is not recorded here, has read repositories, issues, pull requests and
review threads and added pull request comments, as an account **without** the
administrator flag.

That is the whole of the claim, and it is narrower than "the connector
works":

- **Refresh and revocation are not established by it.** Neither was observed
  on either connection: no rotation, no replay, no revocation. For the ChatGPT
  connection that is a statement about an OAuth grant. For the second client
  it is simply that nothing of the kind was seen — its authentication path is
  not recorded here, so it is not claimed to hold a grant at all. Rotation,
  replay and revocation do have evidence — `itest/oauth_controls.py` over raw
  HTTP — but none of it from a commercial client.
- **Neither is the permission matrix.** One administrator and one ordinary
  account are two accounts, not the visibility × role × `issuesOption` matrix.
  A read that succeeds for an administrator says nothing about a guest. That
  matrix is `itest/integration.py`, which drives every tool against every
  role, and the refused writes are confined to disposable instances.
- **Nor anything about this source.** The GitBucket these clients reached
  carries an **earlier build** of this plugin. Its `list_repositories`
  answers still have no `is_fork` field and no `*_semantics` keys, and still
  report zero issues and zero pull requests for repositories that have both —
  the defect the counter correction fixed. So these are observations about the
  authorization flow and the tool surface *on the build that was installed at
  the time*. Installing this tree is a separate, deliberate step, and it has
  not been taken.

A separate gate, `itest/resource_gate.py`, covers the audience check the way
only a restart can: one server issues a grant while it calls itself one
resource, the same server restarts as another, and the unexpired credential is
presented again. It then restores the original configuration and checks that
the refused credentials still work, so a refusal is a refusal rather than a
quiet destruction.

### The guards have been shown to fail

A suite that passes says nothing until a broken implementation makes it fail.
Nine single-line mutations of production source — the scope gate permitting
everything, a code's `redirect_uri` binding unchecked, a refresh token's
client binding unchecked, the SSRF fence opened, PKCE accepted without
verification, the request resolving the host again instead of using the
checked address, a spent code judged before its bindings, a rotation failure
ignoring expiry, and the audience of a refresh grant unchecked — each turn the
suite red on the controls written for that guard, and the source is restored
byte-for-byte afterwards. The first of those mutations originally left the
suite green, which is how the scope gate turned out to have no offline control
at all; it has one now.

## Configuration

The canonical resource URL is configured explicitly. It is **not** derived
from the `Host` header or from `X-Forwarded-*`, because those are attacker-
controlled on a reverse-proxied deployment and a resource identifier that
moves with the request is not an audience binding. A deployment behind a proxy
or in a servlet context path sets the external URL once; everything else —
issuer, endpoints, the `resource` value in tokens — is derived from it.

| Property | Default | Effect |
| --- | --- | --- |
| `gitbucket.mcp.oauth.baseUrl` | unset | The external base URL. Unset, OAuth is off: discovery answers 404 and Bearer tokens are refused. Basic auth is unaffected. |
| `gitbucket.mcp.oauth.allowLoopbackClients` | `false` | Permits a `client_id` on a loopback address, and nothing else. |

`GITBUCKET_MCP_OAUTH_BASE_URL` is read if the system property is unset. The
URL must be `https://`, except on `localhost` or `127.0.0.1` where plain HTTP
is allowed so the suites can run, and it must carry no trailing slash, query
or fragment. A value that fails any of these is rejected with a reason rather
than normalised, and OAuth stays off — a bearer token on a cleartext
connection is a bearer token for whoever is on the path, and a base URL that
quietly becomes something else is not an audience binding.
