# cycle-trader

Triangular arbitrage between Nobitex's **rial (IRT)** and **USDT** markets.

Nobitex quotes every coin twice -- once against rial (`BTCIRT`) and once against
USDT (`BTCUSDT`) -- and quotes USDT itself against rial (`USDTIRT`). Whenever
those three prices disagree by more than three legs of fees, there is a closed
loop that starts and ends in rial and comes back with more of it:

```
FORWARD   IRT ──buy──▶ COIN ──sell──▶ USDT ──sell──▶ IRT
                BTCIRT         BTCUSDT         USDTIRT

REVERSE   IRT ──buy──▶ USDT ──buy──▶ COIN ──sell──▶ IRT
                USDTIRT        BTCUSDT         BTCIRT
```

`cycle-trader` streams the top of every relevant order book over Nobitex's
websocket, re-prices both loops for ~150 coins on every tick, and acts on any
that clears a configurable net-profit threshold -- on paper by default, for real
when told to. Every attempt is written to a P&L report as a single realised
number, including attempts that broke halfway and had to be unwound.

---

## How it works

```
                 ┌──────────────────┐
  Nobitex REST ─▶│ MarketDiscovery  │─ bases with both COINIRT + COINUSDT, ranked by volume
                 └────────┬─────────┘
                          ▼
                 ┌──────────────────┐
                 │ TriangleBuilder  │─ 2 triangles per base; currency chain verified
                 └────────┬─────────┘
                          ▼
  Nobitex WS ───▶ OrderBookCache ──▶ ArbitrageDetector ──▶ TradeCoordinator ──▶ Executor
  (295 channels)  best bid/ask       net ratio per loop     dedupe, cooldown,    paper | live
                                                            one at a time             │
                                                                 ▲                    ▼
                                                                 │             RecoveryManager
                                                                 │             (unwind after 10 min)
                                                                 ▼
                                                            PnlReporter ──▶ reports/pnl.csv
```

### The arithmetic

For each leg, with `c = fee + slippage` for that leg's fee schedule:

```
BUY  on market (src/dst) at ask:   out[src] = in[dst] / ask × (1 − c)
SELL on market (src/dst) at bid:   out[dst] = in[src] × bid × (1 − c)
```

Nobitex charges the fee on the asset you *receive*, so the haircut is applied
to every leg's output. The three legs compose, and the loop's net ratio is
`out₃ / in₁ − 1`. An opportunity is anything with net ratio ≥
`detection.min_profit_ratio`.

Rial-quoted legs (`COINIRT`, `USDTIRT`) and USDT-quoted legs (`COINUSDT`) are
on different fee schedules and are configured separately.

### What makes it safe to run

The dangerous part of triangular arbitrage on a spot exchange is that the three
legs are **not atomic**. They are three separate orders against three separate
books, and if leg 2 fails you are holding a coin you did not want. The engine is
built around shrinking and bounding that window:

| concern | what the code does |
| --- | --- |
| one persistent quote fires on every tick | a triangle already queued/running is not queued again, and is ignored for `cooldown_s` after it finishes |
| slow execution starves the websocket | execution runs on a dedicated thread; the websocket thread only enqueues. Nobitex drops a connection that misses its 25s ping |
| two loops spend the same rial | one executor thread means one triangle at a time; free rial balance is checked before leg 1 |
| a leg walks a thin book | legs are limit orders priced `cross_by` through the touch: immediate like a market order, but capped |
| a leg does not fill | after `leg_timeout_s` it is cancelled, its **partial fill is read back exactly**, and the remaining legs are skipped |
| sizing on an estimate | each leg is sized from the previous leg's *reported* net proceeds, never from the detector's numbers, so an order can never exceed what the wallet holds |
| a retried order double-fills | nothing is retried on a transport error. Failures are reported and handed to recovery |
| stranded inventory | after `recovery_delay_s` (default 10 min), every stranded asset is sold on its direct IRT market for `min(recorded, wallet free)`; retried `recovery_max_attempts` times; then the **whole episode** is reported as one P&L row |
| stacked losses in one coin | a base with a recovery pending is paused until the recovery completes |
| rate limits | local budget of `max_orders_per_10min` (Nobitex's shared limit is 300) |
| sub-tick prices | limit prices are rounded to the number of decimals Nobitex itself prints for that book, in the aggressive direction |
| scientific notation | prices and amounts are serialised as plain decimals -- a `double` would send a rial price as `1.74E11` |

---

## Quick start

Requirements: JDK 21+, Maven 3.9+.

```bash
mvn -q package
java -jar target/cycle-trader.jar                 # paper mode, config/config.yaml
java -jar target/cycle-trader.jar path/to/other.yaml
```

Paper mode needs no credentials. You will see:

```
starting cycle-trader in PAPER mode
223 candidate base coins with both IRT and USDT books
universe: 147 triangles (x2 directions) after volume/limit filtering
subscribing to 295 orderbook channels; P&L rows go to reports/pnl.csv
websocket connected, subscribing to 295 channel(s)
feed: 6993 book updates so far across 294 of 295 symbols
OPPORTUNITY LINK/FORWARD start=5000000 IRT final=5008400 IRT profit=0.168% (8400 IRT)
PNL E00001 IRT>LINK>USDT>IRT FILLED spent=5000000 back=5008400 pnl=8400 IRT (0.168%, expected 0.168%)
```

Run paper for a while before anything else. If it never fires at the default
0.15% threshold, that is a real finding about the market, not a bug -- see
[Reading the results](#reading-the-results).

### Going live

```bash
cp .env.example .env             # then fill in both keys
```

```
NOBITEX_API_KEY=...              # public half of the Ed25519 API key pair
NOBITEX_API_SECRET=...           # private half -- shown ONCE when the key is created
```

Set `mode: "live"` in `config/config.yaml`. On start the bot prints your free
rial balance and the per-triangle notional, then waits 10 seconds for a Ctrl+C.

Credentials are never read from YAML. Real environment variables take
precedence; `.env` in the working directory fills in whatever is missing, and
is gitignored. An API key is not a bearer token: every request carries an
Ed25519 signature over `timestamp + method + path + body`, so both halves are
required. Both are 44-character base64 strings that look identical; using the
public half as the secret produces signatures the exchange rejects with a bare
401.

The free rial balance must exceed `detection.trade_notional_irt` or every
triangle is refused before its first order (and logged as such).

Start with a `trade_notional_irt` you are willing to lose in full, on a
`max_symbols` of one or two liquid coins, and read every row of the report.

---

## Configuration

All keys live in [`config/config.yaml`](config/config.yaml); every one is
documented inline and again in
[`AppConfig.java`](src/main/java/org/sarh/cycle/config/AppConfig.java) with its
default. The ones that decide whether you make or lose money:

| key | default | why it matters |
| --- | --- | --- |
| `fees.taker_fee_irt` / `taker_fee_usdt` | 0.10% / 0.09% | Your **30-day-volume tier**. The whole edge is a few tenths of a percent; a wrong fee here makes every opportunity a phantom. |
| `fees.slippage` | 0.10% | Per-leg haircut beyond the touch. Increase it for thin coins. |
| `detection.min_profit_ratio` | 0.15% | Net threshold after all three legs. Below ~0.1% you are trading noise. |
| `detection.trade_notional_irt` | 5,000,000 | Rial per triangle. Depth is not checked -- keep it under the visible size at the touch of the thinnest leg. |
| `detection.min_order_rial` | 550,000 | Nobitex's real rial-order floor, **measured** at ~500,000 (398,030 rejected, 497,538 accepted). The documented 3,000,000 is 6x too high; used as a floor it leaves small positions unsellable. |
| `detection.min_order_usdt` | 11 | Nobitex's USDT-market minimum. If the middle leg is below this, leg 1 fills and leg 2 is rejected. **Verify for your account.** |
| `execution.cross_by` | 0.5% | How far through the touch a leg may fill. Lower = fewer fills, higher = worse fills. |
| `execution.recovery_delay_s` | 600 | How long stranded inventory sits before it is sold back to rial. |
| `universe.min_24h_rial_volume` | 5e9 | Drops the illiquid tail where top-of-book prices mean nothing. |

---

## Reading the results

### `reports/pnl.csv`

One row per **episode** -- a triangle attempt, from the first order to the
point where the account is back in rial. Aborted attempts are held open until
their recovery finishes, then written as one row, so `pnl_irt` is always
realised, never marked.

| column | meaning |
| --- | --- |
| `status` | `FILLED` · `ABORTED_RECOVERED` · `ABORTED_PARTIAL_RECOVERY` · `ABORTED_UNRECOVERED` · `ABORTED_NOTHING_SPENT` |
| `expected_pct` | net profit the detector priced in |
| `irt_spent` | rial actually consumed by leg 1 |
| `irt_received` | rial produced by leg 3 (0 if the loop broke) |
| `irt_recovered` | rial produced by unwinding stranded assets |
| `pnl_irt`, `pnl_pct` | `irt_received + irt_recovered − irt_spent` |
| `leg1..leg3` | side, market, status, in, out, average price |
| `recovery` | the unwind legs, if any |
| `note` | anything that needs a human, e.g. `STILL STRANDED, manual action needed: {btc=0.0012}` |

`expected_pct` vs `pnl_pct` is the number to watch. A consistent gap is your
real slippage; put it in `fees.slippage`.

### Log summary

Every `report.summary_interval_s` (and at shutdown):

```
PNL SUMMARY by path
  BTC/FORWARD        n=12   wins=9    aborted=1   pnl=        41,230 IRT  (0.069% of spent)
  LINK/REVERSE       n=3    wins=3    aborted=0   pnl=        19,880 IRT  (0.133% of spent)
  TOTAL              n=15   wins=12   aborted=1   pnl=        61,110 IRT  (0.081% of spent)
```

### If paper never fires

Three legs at ~0.1% each plus slippage is roughly 0.5–0.6% of round-trip cost.
A loop only clears that when the USDTIRT rate and a coin's implied rate
(`COINIRT / COINUSDT`) disagree by more than that -- which happens during fast
rial moves, not in quiet markets. The scanner working correctly in a quiet
market looks like silence. Lower `min_profit_ratio` to see near-misses, but do
not trade them.

---

## Project layout

```
config/config.yaml                      every knob, documented
src/main/java/org/sarh/cycle/
  App.java                              wiring and lifecycle
  config/AppConfig.java                 typed config, defaults, validation
  market/MarketDiscovery.java           live market list → ranked base coins + precision
  model/
    Triangle.java                       3 legs; constructor verifies the currency chain
    TriangleLeg.java                    market + side; input/output currency
    Opportunity.java                    a priced loop
    LegResult.java                      in / consumed / out, net of fee; partials explicit
    TriangleExecutionResult.java        3 legs + stranded-inventory calculation
    TradeEpisode.java                   the unit of P&L reporting
  ws/
    CentrifugoWebSocketClient.java      Centrifugo v2 over java.net.http.WebSocket
    OrderBookCache.java                 best bid/ask, price decimals, receive time
  rest/
    NobitexAuth.java                    Ed25519 request signing
    NobitexRestClient.java              market list, orders, balances
  engine/
    TriangleBuilder.java                FORWARD + REVERSE per base
    ArbitrageDetector.java              the arithmetic
    TradeCoordinator.java               dedupe, cooldown, single executor thread
    PaperExecutor.java                  fills at detector prices, never fails
    LiveExecutor.java                   real orders, one leg at a time
    RecoveryManager.java                unwind stranded inventory, then report
  report/PnlReporter.java               CSV rows + per-path summary
```

---

## Nobitex specifics worth knowing

- **Units.** Order books, market stats and the trading API speak **rial**.
  Only the OHLC/candle endpoints speak toman -- this project does not use them,
  so no conversion exists anywhere in the code. `dstCurrency: "rls"` is rial.
- **Websocket.** Centrifugo v2, JSON. Pushes carry their payload as a JSON
  *string* inside `pub.data` (double-decode). The server pings with `{}` and
  drops you if `{}` does not come back within 25 s. `java.net.http.WebSocket`
  refuses a second `sendText` while one is pending, so all sends are chained.
- **Order fields.** `amount` is always in the *src* currency, for both sides.
  `/market/orders/status` takes `id`; `/market/orders/update-status` (cancel)
  takes `order`. `clientOrderId` lookups only see open orders, so the numeric
  id from the placement response is preferred for status polls.
- **Fees** are charged on the asset received: the bought coin on a buy, the
  quote currency on a sell (verified on live fills: 0.10% rial / 0.09% USDT on
  a base-tier account). The reported `fee` is used when it is sane, and the
  configured rate when it is larger -- under-estimating proceeds leaves dust;
  over-estimating gets the next leg rejected.
- **Wallet balances lag fills** by several seconds. Read right after a burst of
  orders, `/users/wallets/list` showed ~25% less than the orders' own records
  and caught up within a minute. Leg sizing therefore never reads the wallet;
  it uses the previous order's reported proceeds.
- **Minimum order** on rial markets is ~500,000 rial (measured), not the
  documented 3,000,000. Some USDT markets reject small sells with an opaque
  `Order Validation Failed`; the engine treats that as a failed leg.
- **Bots** should identify as `User-Agent: TraderBot/<name>`.

---

## Known limitations

- **Top-of-book only.** Legs are valued at the best bid/ask with no depth
  check. `trade_notional_irt` must stay below the visible size at the touch, or
  fills land inside the `cross_by` band and the edge evaporates. Depth-aware
  sizing from the full book is the most valuable next step.
- **Scaled markets are skipped** (`1K_SHIB`, `1M_PEPE`, `100K_FLOKI`, ...).
  Their unit multiplier has to be applied identically on the IRT and USDT legs
  and in order sizing; until that is modelled they are excluded by
  `universe.exclude_prefixed`.
- **Paper mode never fails a leg.** Its P&L is an upper bound on what the same
  signals would have earned live.
- **Sequential legs.** Even at their fastest, three round trips take on the
  order of a second. An edge that lasts less than that is not capturable here.
- **Fee tier is assumed, not read.** Set `fees.*` from your account.

---

## Disclaimer

This is trading software. It can lose money, including all of the
`trade_notional_irt` on a single broken loop. Run it in paper mode until you
have read every row it writes and understand every interlock above. Never
deploy capital you cannot afford to lose.
