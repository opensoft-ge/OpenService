# Third-party notices

OpenService's original application code is offered under the root [Apache License 2.0](LICENSE). That license does not replace the licenses of third-party components.

## Earlier OpenService material

Earlier revisions were distributed under MIT. The original copyright and permission notice is retained in [licenses/MIT-previous-OpenService.txt](licenses/MIT-previous-OpenService.txt) and packaged with the application. The Apache 2.0 release does not revoke prior MIT grants.

## Source-distributed tooling

`mvnw` and `mvnw.cmd` are Apache Maven Wrapper 3.3.4 scripts. Their original headers are retained. The complete [Apache License 2.0](licenses/Apache-2.0.txt) and upstream [Maven Wrapper NOTICE](licenses/maven-wrapper-NOTICE.txt) accompany this repository. The wrapper downloads Maven; its version and distribution checksum are pinned in `.mvn/wrapper/maven-wrapper.properties`.

The license and notice were retrieved from the Apache Maven Wrapper `maven-wrapper-3.3.4` tag on 2026-11-14; trailing whitespace in the notice was normalized without changing its text:

- https://github.com/apache/maven-wrapper/blob/maven-wrapper-3.3.4/LICENSE
- https://github.com/apache/maven-wrapper/blob/maven-wrapper-3.3.4/NOTICE

## Resolved application dependencies

[The runtime license inventory](docs/runtime-licenses.json) records the 61 runtime/repackaging artifacts inspected for the Java build, with license declarations from their published Maven POMs and parent POMs. Bouncy Castle's notice was extracted from its shipped `org.bouncycastle.LICENSE` class; a [copy is included](licenses/Bouncy-Castle.txt). Spring Boot's repackaging tools use the project's Apache 2.0 license.

Most dependencies declare Apache 2.0. The SMTP integration adds Angus/Jakarta Mail (EPL 2.0 / GPL2 with Classpath Exception / EDL 1.0 declarations), Angus/Jakarta Activation (EDL 1.0), and Spring mail/context support (Apache 2.0). The updated inventory was reconciled with the packaged JAR on 2026-11-15. SLF4J and Checker Framework annotations declare MIT; PostgreSQL JDBC declares BSD-2-Clause. Logback declares EPL-2.0 / LGPL-2.1 alternatives, and Jakarta Annotations declares EPL-2.0 / GPL2 with Classpath Exception alternatives. The inventory preserves those declarations rather than assigning the application's Apache 2.0 licence to dependencies. Consult the actual component license for its options and obligations.

Dependencies are resolved from Maven repositories; their source is not vendored in this repository. A packaged Spring Boot JAR contains nested dependency JARs with their own metadata and available notices. Binary distributors must preserve applicable notices and satisfy the selected dependency licenses, including any source availability obligations. A POM inventory is evidence about declarations, not a legal clearance or a complete binary-distribution compliance audit.

The browser coverage converter and axe-core accessibility checker are development-only. Axe-core 4.14.0 declares MPL-2.0; it is loaded only by automated tests and is not shipped to application users. Its exact `v8-to-istanbul` dependency tree, package licenses and integrity hashes are recorded in `tools/browser-coverage/package-lock.json`; `node_modules` is excluded from source publication and application packaging.

## OIDC federation dependency review — 18 November 2026

The federation slice adds Nimbus OAuth2/OIDC SDK11.39, JOSE/JWT10.9.1, content-type2.3, lang-tag1.7, JCIP annotations1.0-1, json-smart2.6.0 and accessors-smart2.6.0 with their published Apache2.0 declarations. ASM9.7.1 declares BSD-3-Clause; its [upstream license](licenses/ASM-BSD-3-Clause.txt) is included. The runtime dependency list and published Maven POM license declarations were checked at this revision. These libraries are dependencies, not copied application source. Optional cryptography/SAML dependencies were not added. Nimbus performs signature and standard ID-token validation; OpenService adds its original binding, state/PKCE, current-authority and configured-provider boundaries. Packaged dependency reconciliation remains a release qualification step.

## Shared browser sessions dependency review — 18 November 2026

Spring Session Core 4.1.1 adds the HttpSession adapter. Its published Maven POM declares Broadcom Foundation License without a URL, while its shipped `META-INF/LICENSE.txt` and official 4.1.1 documentation state Apache License 2.0. The upstream POM was retrieved over verified HTTPS and matches the local resolved POM. The [unaltered shipped license](licenses/spring-session-core-4.1.1-LICENSE.txt) is retained. The inventory records both pieces of evidence; the discrepancy remains unresolved and no legal clearance is asserted. Package reconciliation is recorded separately in the final batch qualification.
