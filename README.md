# IndianEconomyPlugin

Paper plugin source project targeting Minecraft Java 26.3.

## Build
Requires JDK 25 and internet access. Run:

```bash
./gradlew build
```

The shaded plugin JAR is written to `build/libs/IndianEconomyPlugin-1.0.0.jar`.

## Install
1. Build the JAR.
2. Stop your Paper server.
3. Put the JAR in `plugins/`.
4. Start the server and review the console.

## Current implementation scope
This initial source commit is a foundation: SQLite-backed balances, /bal, /pay, /daily, /shop GUI, and basic /ie admin balance commands. Auction house, player market, order book, sell-all, transaction browser, advanced shop pagination/search, and full automated server integration tests are not yet implemented.

Do not deploy to a public economy server until the plugin has been built and tested on a disposable Paper 26.3 server. Paper 26.3 requires Java 25.
