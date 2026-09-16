#!/usr/bin/env python3
"""Fill a local database with suppliers, stores and offers.

The V7 migration seeds the *canonical* catalog — the platform's product list —
but nothing sells anything. Search, comparison, cart and checkout all need live
offers, so a freshly migrated local database shows empty screens that look like
bugs in the app.

This walks the real API for everything a supplier can legitimately do. The one
exception is activation: a supplier goes live only after a platform admin reviews
its verification, and there is no admin account on a fresh local database to do
the reviewing. That single step is a direct UPDATE, and it is marked below.

Usage:
    python3 tools/seed-local.py                 # against localhost:7070
    API=http://localhost:7070 python3 tools/seed-local.py
"""

import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

API = os.environ.get("API", "http://localhost:7070") + "/costonomy-mp-api/api/v1"
MYSQL_CONTAINER = os.environ.get("MYSQL_CONTAINER", "jobs-mysql")
MYSQL_DB = os.environ.get("MYSQL_DATABASE", "costonomy_mp")
OTP = os.environ.get("OTP", "123456")
RESPONSE_SLA_SECONDS = int(os.environ.get("RESPONSE_SLA_SECONDS", "1800"))


class ApiError(Exception):
    def __init__(self, path, error):
        super().__init__(f"{path} failed: {json.dumps(error, indent=2)}")
        self.code = (error or {}).get("code")
        self.details = (error or {}).get("details") or {}


def call(path, body=None, token=None, method=None):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(
        API + path, data=data, method=method or ("POST" if data else "GET")
    )
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(request) as response:
            payload = json.load(response)
    except urllib.error.HTTPError as error:
        raise ApiError(path, json.load(error).get("error"))
    if payload.get("error"):
        raise ApiError(path, payload["error"])
    return payload["data"]


def sql(statement):
    """The activation step only. Everything else goes through the API."""
    subprocess.run(
        ["docker", "exec", "-i", MYSQL_CONTAINER, "mysql", "-uroot", "-proot",
         MYSQL_DB, "-e", statement],
        check=True, capture_output=True,
    )


def login(phone):
    """Sign in, waiting out the resend cooldown rather than failing on it.

    The cooldown is deliberately left real in the local profile, and re-running
    this script inside a minute is the normal case, not the exceptional one.
    """
    try:
        call("/auth/otp/request", {"phone": phone, "purpose": "LOGIN"})
    except ApiError as error:
        if error.code != "OTP_RESEND_TOO_SOON":
            raise
        wait = int(error.details.get("retryAfterSeconds", 60)) + 1
        print(f"  waiting {wait}s for {phone}'s resend cooldown")
        time.sleep(wait)
        call("/auth/otp/request", {"phone": phone, "purpose": "LOGIN"})

    session = call("/auth/otp/verify", {"phone": phone, "otp": OTP, "purpose": "LOGIN"})
    return session["accessToken"]


# Hyderabad, so the outlets and stores are close enough for delivery quoting to
# return something. Prices differ per supplier on purpose — a comparison screen
# with identical prices proves nothing.
SUPPLIERS = [
    {
        "phone": "+919876511001",
        "name": "Sri Balaji Traders",
        "store": "Domlur",
        "lat": "12.9611", "lng": "77.6387",
        "multiplier": 1.00,
    },
    {
        "phone": "+919876511002",
        "name": "Metro Fresh Supplies",
        "store": "Koramangala",
        "lat": "12.9352", "lng": "77.6245",
        "multiplier": 0.94,
    },
    {
        "phone": "+919876511003",
        "name": "Deccan Wholesale",
        "store": "Whitefield",
        "lat": "12.9698", "lng": "77.7500",
        "multiplier": 1.07,
    },
]

# The demo restaurant. Coordinates matter: delivery quoting is a real
# serviceability check against the distance between store and outlet, so an
# outlet without them can never be quoted for and the lifecycle stops dead at
# READY_FOR_PICKUP.
RESTAURANT = {
    "phone": "+919876500004",
    "name": "Spice Garden",
    "outlet": "Indiranagar",
    "lat": "12.9784", "lng": "77.6408",
}

# canonical product id -> (sku code, base price, gst rate, pack size, pack unit)
STOCK = {
    4:  ("PNR-1KG", "410.00", "5", 1, "KG"),
    5:  ("CRM-1L", "260.00", "5", 1, "L"),
    7:  ("CRD-1KG", "90.00", "5", 1, "KG"),
    8:  ("BTR-1KG", "520.00", "12", 1, "KG"),
    9:  ("MLK-1L", "56.00", "5", 1, "L"),
    10: ("BAS-25KG", "2450.00", "5", 25, "KG"),
    11: ("SON-25KG", "1380.00", "5", 25, "KG"),
    12: ("MAI-25KG", "960.00", "5", 25, "KG"),
    14: ("TOO-25KG", "3200.00", "5", 25, "KG"),
    16: ("CHK-1KG", "240.00", "0", 1, "KG"),
    17: ("CHB-1KG", "330.00", "0", 1, "KG"),
    19: ("SUN-15L", "1890.00", "5", 15, "L"),
    21: ("GHE-1KG", "640.00", "12", 1, "KG"),
    24: ("RCP-1KG", "310.00", "5", 1, "KG"),
    25: ("TUR-1KG", "280.00", "5", 1, "KG"),
    28: ("SUG-50KG", "2300.00", "5", 50, "KG"),
    30: ("ONI-1KG", "34.00", "0", 1, "KG"),
    31: ("TOM-1KG", "28.00", "0", 1, "KG"),
    32: ("POT-1KG", "26.00", "0", 1, "KG"),
    33: ("GIN-1KG", "120.00", "0", 1, "KG"),
    34: ("GAR-1KG", "180.00", "0", 1, "KG"),
    22: ("FOI-500", "1450.00", "18", 500, "PIECE"),
    23: ("BAG-500", "820.00", "18", 500, "PIECE"),
    1:  ("DWL-5L", "420.00", "18", 5, "L"),
}


def existing_supplier(token):
    """The supplier this user already owns, if any.

    Re-running the seed is normal — after a migration, after a wipe of one table,
    or just to top up SKUs. Without this check each run created a *new* supplier
    organisation for the same person, and the catalog filled up with three
    identical "Metro Fresh Supplies" competing with each other in search.
    """
    me = call("/auth/me", token=token)
    for membership in me.get("memberships", []):
        if membership["scopeType"] == "SUPPLIER" and membership["scopeId"]:
            return call(f"/suppliers/{membership['scopeId']}", token=token)
    return None


def seed_supplier(spec):
    token = login(spec["phone"])

    already = existing_supplier(token)
    if already is not None:
        supplier_id = already["id"]
        store_id = already["stores"][0]["id"]
        enable_credit(token, store_id)
        print(f"  {spec['name']}: reusing supplier {supplier_id}, store {store_id}")
        return stock_store(token, supplier_id, store_id, spec)

    created = call("/suppliers", {
        "legalName": spec["name"] + " Pvt Ltd",
        "displayName": spec["name"],
        "firstStore": {
            "name": spec["name"] + " — " + spec["store"],
            "addressLine1": spec["store"] + " Main Road",
            "city": "Bengaluru",
            "state": "Karnataka",
            "pincode": "560071",
            "latitude": spec["lat"],
            "longitude": spec["lng"],
            # The platform default is 60 seconds, which is right in production and
            # useless on a laptop: an order placed while you switch accounts to the
            # supplier app has already expired by the time you get there. This is a
            # real per-store setting (doc 13), not a test hook, so raising it locally
            # changes nothing about how the deadline is enforced — only how long it is.
            "responseSlaSeconds": RESPONSE_SLA_SECONDS,
        },
    }, token=token)

    supplier_id = created["id"]
    store_id = created["stores"][0]["id"]

    # The one direct write. A supplier goes live only after a platform admin
    # reviews its verification, and a fresh local database has no admin account
    # to do the reviewing — see POST /admin/suppliers/{id}/activate for the path
    # a real supplier takes.
    sql("update supplier_organization set lifecycle_status = 'ACTIVE', "
        f"verification_status = 'VERIFIED' where id = {supplier_id}")

    return stock_store(token, supplier_id, store_id, spec)


def stock_store(token, supplier_id, store_id, spec):
    stocked = 0
    for product_id, (code, price, gst, pack_size, pack_unit) in STOCK.items():
        adjusted = f"{float(price) * spec['multiplier']:.2f}"
        try:
            call(f"/supplier-stores/{store_id}/skus", {
                "canonicalProductId": product_id,
                "skuCode": f"{code}-{supplier_id}",
                "name": code,
                "packSize": pack_size,
                "packUnit": pack_unit,
                "sellingPrice": adjusted,
                "gstRate": gst,
            }, token=token)
            stocked += 1
        except ApiError as error:
            # Re-running the seed is normal; an existing SKU is not a failure.
            if error.code not in ("CONFLICT", "DUPLICATE_SKU", "DUPLICATE_SKU_CODE",
                                  "VALIDATION_ERROR"):
                raise

    enable_credit(token, store_id)
    print(f"  {spec['name']}: supplier {supplier_id}, store {store_id}, {stocked} SKUs")
    return supplier_id


def enable_credit(token, store_id):
    """Offer credit terms from this store.

    A store with no policy refuses every credit request with "this supplier
    doesn't offer credit terms", which is correct — and makes the whole credit
    half of the app unreachable on a fresh local database.
    """
    call(f"/supplier-stores/{store_id}/credit-policy", {
        "creditEnabled": True,
        "defaultCreditLimit": "50000.00",
        "defaultCreditPeriodDays": 30,
        "defaultGracePeriodDays": 5,
        "maxSingleOrderCredit": "25000.00",
        "maxOverdueAmount": "10000.00",
        "autoSuspendEnabled": True,
    }, token=token, method="PUT")


def seed_restaurant():
    """A restaurant with a located outlet, so the whole journey is walkable."""
    token = login(RESTAURANT["phone"])

    me = call("/auth/me", token=token)
    for membership in me.get("memberships", []):
        if membership["scopeType"] == "RESTAURANT" and membership["scopeId"]:
            restaurant = call(f"/restaurants/{membership['scopeId']}", token=token)
            outlet = restaurant["outlets"][0]
            if outlet.get("latitude") is None:
                call(f"/outlets/{outlet['id']}", {
                    "latitude": RESTAURANT["lat"],
                    "longitude": RESTAURANT["lng"],
                }, token=token, method="PATCH")
                print(f"  {restaurant['name']}: located existing outlet {outlet['id']}")
            else:
                print(f"  {restaurant['name']}: reusing outlet {outlet['id']}")
            return

    created = call("/restaurants", {
        "name": RESTAURANT["name"],
        "firstOutlet": {
            "name": RESTAURANT["outlet"],
            "addressLine1": RESTAURANT["outlet"] + " 100 Feet Road",
            "city": "Bengaluru",
            "state": "Karnataka",
            "pincode": "560038",
            "latitude": RESTAURANT["lat"],
            "longitude": RESTAURANT["lng"],
        },
    }, token=token)
    print(f"  {created['name']}: restaurant {created['id']}, outlet {created['outlets'][0]['id']}")


OPERATOR_PHONE = "+919876599001"


def seed_operator():
    """A local operations account, so delivery simulation is reachable.

    Doc 06 §11's simulation endpoints need DELIVERY_OPERATE at PLATFORM scope,
    which no tenant role holds and no local database has anyone in. Without an
    operator the delivery lifecycle stops at READY_FOR_PICKUP and everything past
    it — tracking, receiving, rating, disputes — is unreachable outside the Java
    suite.

    Granted by direct insert because granting a platform role is deliberately not
    an API a tenant can call. That is the same reason this only ever runs against
    a local database.
    """
    token = login(OPERATOR_PHONE)
    user_id = call("/auth/me", token=token)["user"]["id"]

    sql(
        "insert into user_role (user_id, role_id, scope_type, scope_id, status, "
        "granted_at, created_at, updated_at, version) "
        "select %d, r.id, 'PLATFORM', null, 'ACTIVE', now(6), now(6), now(6), 0 "
        "from role r where r.code = 'OPS_ADMIN' "
        "and not exists (select 1 from user_role ur where ur.user_id = %d "
        "and ur.role_id = r.id and ur.scope_type = 'PLATFORM')" % (user_id, user_id)
    )
    print(f"  operations account {OPERATOR_PHONE} (user {user_id}) — OPS_ADMIN at platform scope")


def main():
    print(f"Seeding {API}")

    # Reachability is checked without requesting an OTP. A probe that logs in
    # burns the first supplier's 60-second resend cooldown, and the seed then
    # fails on its own warm-up.
    try:
        urllib.request.urlopen(
            os.environ.get("API", "http://localhost:7070")
            + "/costonomy-mp-api/actuator/health", timeout=5)
    except urllib.error.HTTPError:
        pass  # DOWN is still an answer — something is listening.
    except OSError:
        raise SystemExit(f"Nothing is answering at {API}. Start the API on the `local` profile.")

    for spec in SUPPLIERS:
        seed_supplier(spec)

    seed_restaurant()
    seed_operator()

    print("\nDone. Sign in on the app with any number; the OTP is", OTP)


if __name__ == "__main__":
    main()
