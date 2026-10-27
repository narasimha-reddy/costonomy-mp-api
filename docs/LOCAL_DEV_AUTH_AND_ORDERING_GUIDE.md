# Local Development: Authentication, Operating Hours & Order Placement Guide

This guide documents the local authentication flow (mock OTP) and the order placement validation rules (supplier verification, operating hours, tradeability) in the Costonomy Marketplace.

---

## 1. Local Authentication

### 1.1 Overview
Sign-in is costonomy-common-api's (D-70 in common's DECISIONS): one Costonomy account across the marketplace and
the other apps. Marketplace has no sign-in endpoints of its own; it accepts common's access tokens. Users,
restaurants, suppliers, outlets and stores are common's too, and Marketplace keeps no copies (D-208): the app
creates and edits them in common. Locally, run both APIs with costonomy-api's `local/run.sh dev` (common-api
on `http://localhost:8082`). In common's dev profile SMS is not sent and the code is always `123456`.

### 1.2 Authentication Flow (costonomy-common-api, client `mp-mobile`)

1. **Send a code**: `POST {common}/v1/auth/otp/send`
   ```json
   { "channel": "SMS", "target": "+919885051802", "purpose": "LOGIN", "clientId": "mp-mobile" }
   ```
   Answers `{ "challengeId": "…", "expiresInSeconds": 300, "resendAfterSeconds": 60 }`.

2. **Verify it**: `POST {common}/v1/auth/otp/verify`
   ```json
   { "challengeId": "…", "code": "123456", "clientId": "mp-mobile" }
   ```
   Answers `accessToken` (a JWT common signs), `refreshToken`, `expiresIn` and the user. A number signing in for the
   first time gets an account.

3. **Use it**: `Authorization: Bearer <accessToken>` on Marketplace's API. `GET /api/v1/auth/me` answers the user
   and their memberships. Renew with `POST {common}/v1/auth/refresh` `{ "refreshToken": "…", "clientId": "mp-mobile" }`;
   the refresh token rotates, so keep the new one.

`tools/seed-local.py` does exactly this for its seeded suppliers.

---

## 2. Order Placement Prerequisites & Validation Flow

### 2.1 The Validation Pipeline (`/validate`)

Before any order can be placed from UI (`POST /api/v1/procurements/{id}/submit`), it must be in **`READY`** status (`submittable = true`).

```mermaid
flowchart TD
    DRAFT["DRAFT (Cart)"] --> VALIDATING["VALIDATING"]
    VALIDATING --> C1{"Supplier Active & Verified?"}
    C1 -- No --> B1["Blocker: SUPPLIER_OFFLINE / SKU_UNAVAILABLE"]
    C1 -- Yes --> C2{"Within Operating Hours?"}
    C2 -- No --> B2["Blocker: Store is closed"]
    C2 -- Yes --> C3{"Price / GST Drift?"}
    C3 -- Yes --> P1["Report Price Changes (requires user accept)"]
    C3 -- No --> READY["READY (submittable = true)"]
    B1 --> DRAFT_BACK["Revert to DRAFT (submittable = false)"]
    B2 --> DRAFT_BACK
    P1 --> DRAFT_BACK
```

When a cart is viewed or refreshed on `/restaurant/checkout/{id}`:
- If `data.submittable` is `false`, the **"Place order and pay"** CTA button is **disabled**.
- If `data.validationStale` is `true`, a prompt card **"Prices need a re-check"** appears.

### 2.2 Five Key Prerequisites for `submittable = true`

| Requirement | Code Check | Failure Result |
| :--- | :--- | :--- |
| **1. Supplier Organization** | `so.lifecycle_status == 'ACTIVE'` | Blocker: `SUPPLIER_OFFLINE` (*"{Supplier} isn't accepting orders right now."*) |
| **2. Supplier Store** | `ss.status == 'ACTIVE'` | Blocker: `SUPPLIER_OFFLINE` |
| **3. Operating Hours** | `hours.isOpenAt(ZonedDateTime.now(OperatingHours.ZONE))` | Blocker: `SUPPLIER_OFFLINE` (*"{Supplier} is closed. They open at {opensAt}."*) |
| **4. Live Offer & Stock** | `offer.isPurchasable()` (`ACTIVE` & `AVAILABLE`) | Blocker: `SKU_UNAVAILABLE` (*"This product is out of stock."*) |
| **5. Price & GST Stability** | `Pricing.differs(...) == false` | Attached to `priceChanges`. Must be accepted via `acceptPriceChanges: true`. |

---

## 3. Supplier Operating Hours (`OperatingHours.java`)

### 3.1 Trading Hours Logic
In `com.costonomy.mp.supplier.domain.OperatingHours`:
- Evaluated against timezone: **`Asia/Kolkata`** (`GMT+05:30`).
- **Default Hours**: When `supplier_store.operating_hours_json` is `NULL` or empty, the default is:
  - **Open**: `10:00` (10:00 AM IST)
  - **Close**: `21:00` (9:00 PM IST)
  - **Days**: Monday through Sunday (all 7 days)
- **Night-time Testing Issue**: If testing after 9:00 PM IST, default-hour stores will be evaluated as **closed** (`openNow() = false`).

### 3.2 24/7 Store Configuration
To make a store tradeable 24 hours a day (around the clock) without hour restrictions, set `opensAt` and `closesAt` to identical times (e.g. `00:00` and `00:00`):

```json
{
  "days": ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"],
  "opensAt": "00:00",
  "closesAt": "00:00"
}
```

---

## 4. Quick SQL Setup for Local Development

To immediately unblock checkout testing for Supplier 1 (Bhavana Suppliers) and Store 1 (Bachupally warehouse) at any time of day:

```sql
USE costonomy_mp;

-- 1. Mark verification as completed
UPDATE supplier_verification 
SET status = 'VERIFIED', reviewed_at = NOW(), verified_at = NOW() 
WHERE supplier_organization_id = 1;

-- 2. Activate supplier organization
UPDATE supplier_organization 
SET verification_status = 'VERIFIED', lifecycle_status = 'ACTIVE' 
WHERE id = 1;

-- 3. Set store to ACTIVE and configure 24/7 operating hours
UPDATE supplier_store 
SET status = 'ACTIVE',
    operating_hours_json = '{"days":["MONDAY","TUESDAY","WEDNESDAY","THURSDAY","FRIDAY","SATURDAY","SUNDAY"],"opensAt":"00:00","closesAt":"00:00"}'
WHERE supplier_organization_id = 1;

-- 4. Ensure current offers are active and available
UPDATE supplier_offer 
SET status = 'ACTIVE', availability = 'AVAILABLE' 
WHERE supplier_store_id = 1;
```

---

## 5. Summary Checklist for UI Checkout Testing

If the **"Place order and pay"** button is disabled on `http://localhost:7071/restaurant/checkout/{id}`:
1. Check if the **"Prices need a re-check"** card is visible. If so, click **"Re-check prices"**.
2. If a red warning appears:
   - *"is closed. They open at 10:00"*: Update `supplier_store.operating_hours_json` to 24/7 (see Section 4).
   - *"isn't accepting orders right now"*: Activate the supplier organization (`lifecycle_status = 'ACTIVE'`).
   - *"out of stock"*: Set `supplier_offer.availability = 'AVAILABLE'`.
3. Once the checks pass, the backend returns `submittable: true` with status `READY`, enabling the submission CTA.
