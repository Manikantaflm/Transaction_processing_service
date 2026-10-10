# Ledgerline UI

React + Vite + Framer Motion operations UI for the Java/PostgreSQL transaction API.

## Run locally
1. Install Node.js 20+.
2. From this folder run `npm install`.
3. Run `npm run dev` and open the URL Vite prints (normally `http://localhost:5173`).
4. Enter the API base URL (normally `http://localhost:8080`), the `API_BEARER_TOKEN`, and the `OPS_BEARER_TOKEN` from your local `.env` file. Tokens are kept in component memory only; they are not persisted.
5. Ensure the backend is running and allows browser requests from the Vite origin. For local browser use, configure backend CORS for `http://localhost:5173` or serve the built UI from the same origin. Do not use wildcard CORS with credentials in production.

## Features
- Live account snapshot, summary counters, recent transaction table, and per-transaction ledger inspection.
- Submit credits/debits of the same amount repeatedly. Each submission creates a fresh transaction ID, request ID, idempotency key, and derives a sequence from the account cursor and visible account history.
- Copy a prior transaction as a **new** transaction; it copies business fields only, never the old identifiers or sequence.
- Displays processing status and API errors. The worker is asynchronous, so ledger entries can be empty immediately after submission.

The UI is an operational assessment interface, not a real-money banking application. Review authentication, CORS, audit, authorization, and deployment controls before production use.
