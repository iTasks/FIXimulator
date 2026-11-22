# FIXimulator

> A Java-based **FIX protocol trading simulator**. It acts as a FIX acceptor (exchange / broker stand-in) so you can configure a server, connect a client, send orders, and test sessions and matching behaviour without touching a live market.

![Java](https://img.shields.io/badge/Java-SE%20desktop-orange)
![Protocol](https://img.shields.io/badge/Protocol-FIX%204.2-blue)
![Version](https://img.shields.io/badge/version-0.41-green)

---

## Table of Contents

- [Features](#-features)
- [Project Goals](#-project-goals)
- [Architecture at a Glance](#-architecture-at-a-glance)
- [Getting Started](#-getting-started)
- [Running the Simulator](#-running-the-simulator)
- [Connecting a FIX Client](#-connecting-a-fix-client)
- [Configuration Reference](#-configuration-reference)
- [Optional: MySQL Logging](#-optional-mysql-logging)
- [Usage Walkthrough](#-usage-walkthrough)
- [Possible Implementations / Roadmap](#-possible-implementations--roadmap)
- [Project Layout](#-project-layout)
- [Troubleshooting](#-troubleshooting)

---

## ✨ Features

| Area | What you get |
| --- | --- |
| 🔌 **FIX Acceptor** | Built on [QuickFIX/J](https://www.quickfixj.org/) with a socket acceptor (default port `9878`) |
| 🧾 **Order handling** | Create, acknowledge, pend, cancel and replace orders |
| 💱 **Executions** | Fill / partial-fill orders manually or automatically |
| 📣 **IOIs** | Indication-of-Interest messages |
| 📈 **Instruments** | Load and manage instruments from `config/instruments.xml` |
| 📜 **Message logs** | Inspect in-flight FIX messages in the UI, with optional file and DB logging |
| 🔍 **Canned queries** | Run prepared reports against the message / event store |
| ⚙️ **Auto-behaviour** | Toggle auto-acknowledge, auto-cancel, auto-replace and auto-pending responses |

---

## 🎯 Project Goals

1. Configure the server
2. Run the server
3. Connect to a client
4. Perform trades
5. Perform session tests
6. Perform matching-engine tests
7. Analyse real-world technologies
8. Adopt new protocols such as **ITCH**, **OUCH** and **SQF**

---

## 🏗 Architecture at a Glance

```
 ┌──────────────────┐      FIX 4.2 over TCP       ┌────────────────────────────┐
 │  Your FIX client │ ──────────────────────────▶ │  FIXimulator (acceptor)    │
 │  (e.g. BANZAI)   │ ◀────────────────────────── │  QuickFIX/J SocketAcceptor │
 └──────────────────┘   acks / fills / reports    │  + Swing control UI        │
                                                  └─────────────┬──────────────┘
                                                                │ optional
                                                                ▼
                                                  ┌────────────────────────────┐
                                                  │ File logs / MySQL (JDBC)   │
                                                  └────────────────────────────┘
```

Startup flow (`FIXimulator` → `FIXimulatorFrame`):

1. Reads `config/FIXimulator.cfg` (QuickFIX/J `SessionSettings`).
2. Loads instruments from `config/instruments.xml`.
3. Builds the message store and log factories (screen, file, JDBC depending on settings).
4. Starts the `SocketAcceptor` and opens the Swing UI.

---

## 🚀 Getting Started

### Prerequisites

- A **Java runtime** capable of running `dist/FIXimulator_0.41.jar` (a Swing desktop app; a display is required).
- *(Optional)* **MySQL** if you want database logging.
- *(Optional)* **NetBeans** or **Apache Ant** if you want to rebuild from source. The project is configured with `javac.source=1.5` in `nbproject/project.properties`, so a legacy JDK may be needed to recompile.

### Repository contents you need

| Path | Purpose |
| --- | --- |
| `dist/FIXimulator_0.41.jar` | Ready-to-run application |
| `dist/lib/` | Runtime libraries (QuickFIX/J, MINA, SLF4J, MySQL connector, …) |
| `config/` | Session and instrument configuration |
| `fiximulator.bat` | Windows launcher |
| `reset.bat` | Clears the `data` folder (session store) |
| `mysql/` | SQL scripts for the optional database |

### Build (optional)

Using NetBeans, open the project and choose **Clean and Build**, or from the command line use the Ant build at the repository root:

```bash
ant clean jar
```

The output is written to `dist/`.

---

## ▶️ Running the Simulator

> **Important:** run the application from the **repository root**. The configuration paths (`config/...`, `data`, `logs`) are relative to the current working directory.

**Linux / macOS**

```bash
java -jar dist/FIXimulator_0.41.jar
```

**Windows**

```bat
fiximulator.bat
```

Or:

```bat
java -jar dist\FIXimulator_0.41.jar
```

When it starts you should see:

- The **FIXimulator** main window (Swing).
- The FIX acceptor listening on **port `9878`** (set by `SocketAcceptPort`).
- Log output in the console and, if enabled, under `logs/`.

To wipe the local session store and start fresh, run `reset.bat` on Windows (or delete `data/`) while the simulator is stopped.

---

## 🔗 Connecting a FIX Client

The default session is configured as:

| Setting | Value |
| --- | --- |
| Protocol | FIX 4.2 (`BeginString=FIX.4.2`) |
| Connection type | `acceptor` (FIXimulator listens) |
| Host | `localhost` |
| Port | `9878` |
| SenderCompID (FIXimulator) | `FIXIMULATOR` |
| TargetCompID (your client) | `BANZAI` |
| Heartbeat interval | `30` seconds |
| Session time window | `00:00:00` – `00:00:00` (always on) |

Your client must log on with **SenderCompID = `BANZAI`** and **TargetCompID = `FIXIMULATOR`**, using FIX 4.2 and a heartbeat of 30 s.

A minimal example from a FIX client's point of view:

```
8=FIX.4.2 | 35=A (Logon) | 49=BANZAI | 56=FIXIMULATOR | 98=0 | 108=30
```

Once the logon is accepted you can send `NewOrderSingle` (`35=D`) messages and receive execution reports (`35=8`).

To use a different counterparty, change `TargetCompID` / `SenderCompID` in `config/FIXimulator.cfg` (or use one of the alternate config files below) and restart.

---

## ⚙️ Configuration Reference

### Configuration files

| File | Purpose |
| --- | --- |
| `config/FIXimulator.cfg` | **Active** configuration loaded at startup |
| `config/FIXimulator_BANZAI.cfg` | Preset for the BANZAI counterparty (port `9878`) |
| `config/FIXimulator_MARKETC.cfg` | Preset for a MARKETC counterparty (port `7001`) |
| `config/instruments.xml` | Full instrument list |
| `config/instruments_small.xml` | Smaller instrument list for quick tests |

To switch presets, copy the one you want over `config/FIXimulator.cfg` before starting.

### Key settings (`[DEFAULT]` section)

| Key | Default | Meaning |
| --- | --- | --- |
| `SocketAcceptPort` | `9878` | TCP port FIXimulator listens on |
| `BeginString` | `FIX.4.2` | FIX version |
| `DataDictionary` | `FIX42.xml` | QuickFIX/J data dictionary |
| `HeartBtInt` | `30` | Heartbeat interval (seconds) |
| `StartTime` / `EndTime` | `00:00:00` | Session active window |
| `FileStorePath` | `data` | Session store directory |
| `RefreshMessageStoreAtLogon` | `Y` | Reset stored messages on logon |
| `FIXimulatorLogToFile` | `N` | Write message logs to `logs/` |
| `FIXimulatorLogToDB` | `N` | Write logs to MySQL via JDBC |
| `FIXimulatorAutoAcknowledge` | `N` | Automatically acknowledge new orders |
| `FIXimulatorAutoCancel` | `N` | Automatically cancel orders |
| `FIXimulatorAutoReplace` | `N` | Automatically accept replace requests |
| `FIXimulatorAutoPendingCancel` | `N` | Automatically handle pending-cancel |
| `FIXimulatorAutoPendingReplace` | `N` | Automatically handle pending-replace |
| `FIXimulatorSendOnBehalfOfCompID` | `N` | Include `OnBehalfOfCompID` in outgoing messages |
| `FIXimulatorSendOnBehalfOfSubID` | `N` | Include `OnBehalfOfSubID` in outgoing messages |
| `FIXimulatorPricePrecision` | `4` | Decimal places for prices |
| `FIXimulatorCachedObjects` | `50` | Number of cached objects |

### Session settings (`[SESSION]` section)

| Key | Example | Meaning |
| --- | --- | --- |
| `SenderCompID` | `FIXIMULATOR` | This side's ID |
| `TargetCompID` | `BANZAI` | Counterparty's ID |
| `FileLogPath` | `logs` | Per-session log directory |
| `OnBehalfOfCompID` | `BROKER` | Used when on-behalf-of is enabled |
| `OnBehalfOfSubID` | `DESK` | Used when on-behalf-of is enabled |

---

## 🗄 Optional: MySQL Logging

Use this only if you set `FIXimulatorLogToDB=Y`.

1. Start MySQL and create the schema and user from `mysql/`:

   ```bash
   cd mysql
   ./create.sh          # Windows: create.bat
   ```

   `create.sh` runs `mysql.sql`, which loads the `quickfix` schema, the `fiximulator` user and the table definitions (`messages_table.sql`, `sessions_table.sql`, `event_log_table.sql`, `messages_log_table.sql`, …).

2. Confirm the JDBC settings in `config/FIXimulator.cfg` match your setup:

   ```ini
   JdbcURL=jdbc:mysql://localhost:3306/quickfix
   JdbcUser=fiximulator
   JdbcPassword=fiximulator
   JdbcDriver=com.mysql.jdbc.Driver
   ```

> ⚠️ The default credentials are for local testing only. Change them before using the simulator anywhere shared.

---

## 🧪 Usage Walkthrough

| Step | Goal | How |
| --- | --- | --- |
| 1 | **Configure** | Pick or edit a config in `config/`; set port and CompIDs |
| 2 | **Run** | Start the jar from the repo root |
| 3 | **Connect** | Log on from your FIX client (`BANZAI` → `FIXIMULATOR`, port `9878`) |
| 4 | **Trade** | Send `NewOrderSingle`; acknowledge, fill, cancel or replace from the UI or enable auto-behaviour |
| 5 | **Session test** | Exercise logon, heartbeats and logout; inspect logs in `logs/` |
| 6 | **Matching-engine test** | Send bursts of orders against `instruments.xml`, verify fills and execution reports |
| 7 | **Analyse** | Review message logs and canned queries to study real-world FIX flows |
| 8 | **Extend** | Add new protocols (see below) |

---

## 🛣 Possible Implementations / Roadmap

These are directions the project can grow in. None are implemented yet.

### Near term: usability and testing

- **Headless mode** — run the acceptor without the Swing UI, for CI pipelines and scripted tests.
- **Scripted scenarios** — describe a sequence of client actions and expected responses in a text file and replay it against the acceptor.
- **Session test harness** — automated checks for logon, heartbeat, sequence-number gaps and resend behaviour.
- **Matching-engine test harness** — a simple price-time-priority book so fills are deterministic and assertable.
- **Upgrade the FIX dialect** — support FIX 4.4 / 5.0 SP2 in addition to 4.2 by selecting `BeginString` and the matching data dictionary.
- **Configurable instruments and prices** — generate `instruments.xml` from a CSV or market-data snapshot.

### Mid term: protocol coverage

| Protocol | Typical use | Possible approach |
| --- | --- | --- |
| **ITCH** | Market-data feed (order-book deltas) | Add a publisher module that replays order events from the matching engine |
| **OUCH** | Low-latency order entry / acknowledgement | Add a binary order-entry gateway alongside the FIX acceptor, sharing the same order model |
| **SQF** | Market-data / quote-stream format | Add a quote-stream encoder fed by the same instrument and book state |

For each protocol the suggested order of work is: define the message model, add an encoder/decoder with round-trip tests, wire it to the existing order and execution pipeline, then expose it through configuration (for example, a new `config/*.cfg` preset).

### Longer term: analysis

- Capture-and-replay of real FIX/ITCH traffic for latency and behaviour comparison.
- Exportable reports (CSV / JSON) from the message and event stores.
- Metrics for throughput, latency and fill rates.

---

## 📁 Project Layout

```
FIXimulator/
├── build.xml                 Ant build (imports nbproject/build-impl.xml)
├── fiximulator.bat           Windows launcher
├── reset.bat                 Clears the session store
├── config/                   Session, preset and instrument configuration
├── dist/                     Built jar and runtime libraries
├── lib/                      Build-time libraries
├── logs/                     Session and message logs
├── mysql/                    Optional DB schema scripts
├── nbproject/                NetBeans project metadata
└── src/edu/harvard/fas/zfeledy/fiximulator/
    ├── core/                 FIX acceptor, orders, executions, IOIs, instruments
    ├── ui/                   Swing front end (FIXimulatorFrame and table models)
    └── util/                 Logging and FIX message helpers
```

---

## 🩺 Troubleshooting

| Symptom | Likely cause / fix |
| --- | --- |
| `FileNotFoundException` for `config/FIXimulator.cfg` on startup | You started the jar from the wrong directory. Run it from the repo root. |
| Client cannot connect | Confirm the port (`9878`) is free and the simulator is running. Check firewall rules. |
| Logon rejected | `SenderCompID` / `TargetCompID` must be the reverse of what the simulator expects (client sends `BANZAI`, simulator is `FIXIMULATOR`). |
| No logs in `logs/` | Set `FIXimulatorLogToFile=Y`. |
| DB logging fails | Check MySQL is running and the `JdbcURL` / user / password match `mysql/fiximulator_user.sql`. |
| Window does not open | The app is a Swing desktop application and needs a graphical display. |

---

<sub>FIXimulator is a learning and testing tool. It is not a production exchange and should not be connected to live trading systems.</sub>
