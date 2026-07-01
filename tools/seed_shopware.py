#!/usr/bin/env python3
"""Seed the local Shopware demo with orders/promos/low-stock so the app has data."""
import json, random, sys, uuid, urllib.request
from datetime import datetime, timedelta, timezone

BASE = "http://localhost:8000"
LANG = "2fbb5fe2e29a4d70aa5854ce7ce3e20b"
random.seed(42)

def req(method, path, body=None, token=None):
    r = urllib.request.Request(BASE + path, method=method)
    r.add_header("Content-Type", "application/json")
    r.add_header("Accept", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(r, data) as resp:
            txt = resp.read().decode()
            return resp.status, json.loads(txt) if txt else {}
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode() or "{}")

def search(entity, criteria, token):
    st, body = req("POST", f"/api/search/{entity}", criteria, token)
    assert st == 200, f"search {entity}: {st} {json.dumps(body)[:400]}"
    return body["data"]

st, tok = req("POST", "/api/oauth/token", {
    "grant_type": "password", "client_id": "administration", "scopes": "write",
    "username": "admin", "password": "shopware"})
assert st == 200, tok
token = tok["access_token"]

sc = search("sales-channel", {"limit": 5}, token)
sc = next((s for s in sc if "Storefront" in (s.get("translated", {}).get("name") or s.get("name") or "")), sc[0])
currency = search("currency", {"limit": 1, "filter": [{"type": "equals", "field": "factor", "value": 1.0}]}, token)[0]
country = search("country", {"limit": 1, "filter": [{"type": "equals", "field": "active", "value": True}]}, token)[0]
payment = search("payment-method", {"limit": 1, "filter": [{"type": "equals", "field": "active", "value": True}]}, token)[0]
salutation = search("salutation", {"limit": 1}, token)[0]

def state(machine, tech):
    return search("state-machine-state", {"limit": 1, "filter": [
        {"type": "equals", "field": "stateMachine.technicalName", "value": machine},
        {"type": "equals", "field": "technicalName", "value": tech}]}, token)[0]["id"]

order_states = {t: state("order.state", t) for t in ["open", "in_progress", "completed"]}
tx_states = {t: state("order_transaction.state", t) for t in ["open", "paid"]}
delivery_states = {t: state("order_delivery.state", t) for t in ["open", "shipped"]}
shipping_method = search("shipping-method", {"limit": 1, "filter": [{"type": "equals", "field": "active", "value": True}]}, token)[0]

# Second sales channel (headless type) so the listing sales-channel filters have data to split on.
# Fixed id keeps this idempotent across re-runs.
SC2 = "0096abcd00112233445566778899aabb"
st, body = req("POST", "/api/_action/sync", {"sc2": {"entity": "sales_channel", "action": "upsert", "payload": [{
    "id": SC2, "name": "Mobile App",
    "typeId": "f183ee5650cf4bdb8a774337575067a6",  # headless API channel type
    "languageId": LANG, "currencyId": currency["id"],
    "paymentMethodId": payment["id"], "shippingMethodId": shipping_method["id"],
    "countryId": country["id"], "customerGroupId": sc["customerGroupId"],
    "navigationCategoryId": sc["navigationCategoryId"],
    "accessKey": "SWSCMOBILEAPPTESTKEY0001",
    "languages": [{"id": LANG}], "currencies": [{"id": currency["id"]}],
    "countries": [{"id": country["id"]}], "paymentMethods": [{"id": payment["id"]}],
    "shippingMethods": [{"id": shipping_method["id"]}],
}]}}, token)
print("second channel:", st, json.dumps(body)[:400] if st >= 400 else "ok")

# Second currency (factor 2.0) so revenue normalization has data to prove itself.
# Fixed id keeps this idempotent across re-runs.
CUR2 = "00c2abcd00112233445566778899aabb"
st, body = req("POST", "/api/_action/sync", {"cur2": {"entity": "currency", "action": "upsert", "payload": [{
    "id": CUR2, "isoCode": "WDK", "symbol": "W$", "shortName": "WDK", "name": "Wonky Dollar",
    "factor": 2.0, "decimalPrecision": 2,
    "itemRounding": {"decimals": 2, "interval": 0.01, "roundForNet": True},
    "totalRounding": {"decimals": 2, "interval": 0.01, "roundForNet": True},
}]}}, token)
print("second currency:", st, json.dumps(body)[:400] if st >= 400 else "ok")

products = search("product", {"limit": 24, "filter": [
    {"type": "equals", "field": "active", "value": True},
    {"type": "equals", "field": "parentId", "value": None}]}, token)
products = [p for p in products if (p.get("price") or [{}])[0].get("gross")]
assert products, "no products with price found"
customers = search("customer", {"limit": 12}, token)
assert customers, "no customers found"
print(f"using {len(products)} products, {len(customers)} customers, channel {sc['id'][:8]}")

def uid(): return uuid.uuid4().hex

def calc_price(unit, qty):
    total = round(unit * qty, 2)
    return {"unitPrice": unit, "quantity": qty, "totalPrice": total,
            "calculatedTaxes": [{"tax": round(total * 0.19 / 1.19, 2), "taxRate": 19.0, "price": total}],
            "taxRules": [{"taxRate": 19.0, "percentage": 100.0}]}

now = datetime.now(timezone.utc)
orders = []
n = 1
for day_back in range(7):
    day = now - timedelta(days=day_back)
    for _ in range(random.randint(3, 8)):
        cust = random.choice(customers)
        # every 9th order is placed in the factor-2 currency (amounts ×2 in WDK)
        fx = 2.0 if n % 9 == 0 else 1.0
        items, total = [], 0.0
        for pos, prod in enumerate(random.sample(products, random.randint(1, 3)), start=1):
            unit = round(float(prod["price"][0]["gross"]) * fx, 2)
            qty = random.randint(1, 3)
            total = round(total + unit * qty, 2)
            items.append({
                "id": uid(), "identifier": uid(), "type": "product",
                "referencedId": prod["id"], "productId": prod["id"],
                "label": (prod.get("translated") or {}).get("name") or prod.get("name") or "Product",
                "payload": {"productNumber": prod.get("productNumber") or "SW-0000"},
                "quantity": qty, "position": pos, "price": calc_price(unit, qty)})
        placed = day.replace(hour=random.randint(8, 19), minute=random.randint(0, 59))
        addr = uid()
        ostate = random.choices(["open", "in_progress", "completed"], weights=[3, 2, 4])[0]
        paid = random.random() > (0.5 if day_back >= 3 else 0.2)
        orders.append({
            "id": uid(), "salesChannelId": SC2 if n % 7 == 0 else sc["id"],
            "currencyId": CUR2 if fx == 2.0 else currency["id"],
            "currencyFactor": fx, "languageId": LANG,
            "orderNumber": str(11000 + n),
            "orderDateTime": placed.strftime("%Y-%m-%d %H:%M:%S.000"),
            "stateId": order_states[ostate],
            "price": {"netPrice": round(total / 1.19, 2), "totalPrice": total,
                      "positionPrice": total, "rawTotal": total, "taxStatus": "gross",
                      "calculatedTaxes": [{"tax": round(total * 0.19 / 1.19, 2), "taxRate": 19.0, "price": total}],
                      "taxRules": [{"taxRate": 19.0, "percentage": 100.0}]},
            "shippingCosts": calc_price(0.0, 1),
            "itemRounding": {"decimals": 2, "interval": 0.01, "roundForNet": True},
            "totalRounding": {"decimals": 2, "interval": 0.01, "roundForNet": True},
            "orderCustomer": {"customerId": cust["id"], "email": cust.get("email") or "demo@example.com",
                              "firstName": cust.get("firstName") or "Demo",
                              "lastName": cust.get("lastName") or "Customer",
                              "salutationId": cust.get("salutationId") or salutation["id"]},
            "billingAddressId": addr,
            "addresses": [{"id": addr, "firstName": cust.get("firstName") or "Demo",
                           "lastName": cust.get("lastName") or "Customer", "street": "Weaver Lane 14",
                           "zipcode": "10115", "city": "Berlin", "countryId": country["id"]}],
            "lineItems": items,
            "transactions": [{"id": uid(), "paymentMethodId": payment["id"],
                              "stateId": tx_states["paid" if paid else "open"],
                              "amount": calc_price(total, 1)}],
            "deliveries": [{
                "id": uid(), "shippingMethodId": shipping_method["id"],
                "stateId": delivery_states["shipped" if (paid and day_back >= 2) else "open"],
                "shippingDateEarliest": (day + timedelta(days=1)).strftime("%Y-%m-%d %H:%M:%S.000"),
                "shippingDateLatest": (day + timedelta(days=3)).strftime("%Y-%m-%d %H:%M:%S.000"),
                "shippingCosts": calc_price(4.9, 1),
                "trackingCodes": (["00340434%010d" % random.randint(1, 10**9)] if (paid and day_back >= 2) else []),
                "shippingOrderAddress": {"id": uid(), "firstName": cust.get("firstName") or "Demo",
                                         "lastName": cust.get("lastName") or "Customer",
                                         "street": "Weaver Lane 14", "zipcode": "10115", "city": "Berlin",
                                         "countryId": country["id"], "phoneNumber": "+49 30 1234567"},
            }],
            **({"customerComment": "Please leave the parcel with the neighbor if not home."} if n % 3 == 0 else {}),
        })
        n += 1

st, body = req("POST", "/api/_action/sync", {"create-orders": {"entity": "order", "action": "upsert", "payload": orders}}, token)
print("orders sync:", st, (json.dumps(body)[:600] if st >= 400 else f"created {len(orders)}"))
if st >= 400:
    sys.exit(1)

# Promotions
def promo(name, value, active, frm, until):
    return {"id": uid(), "name": name, "active": active,
            "validFrom": frm, "validUntil": until,
            "useCodes": False, "useIndividualCodes": False, "useSetGroups": False,
            "salesChannels": [{"id": uid(), "salesChannelId": sc["id"], "priority": 1}],
            "discounts": [{"id": uid(), "scope": "cart", "type": "percentage",
                           "value": value, "considerAdvancedRules": False}]}

promos = [
    promo("Summer Linen", 20.0, True, (now - timedelta(days=10)).strftime("%Y-%m-%d %H:%M:%S.000"),
          (now + timedelta(days=17)).strftime("%Y-%m-%d %H:%M:%S.000")),
    promo("Loyalty Week", 10.0, True, (now - timedelta(days=2)).strftime("%Y-%m-%d %H:%M:%S.000"),
          (now + timedelta(days=4)).strftime("%Y-%m-%d %H:%M:%S.000")),
    promo("Anniversary Preview", 15.0, True, (now + timedelta(days=9)).strftime("%Y-%m-%d %H:%M:%S.000"),
          (now + timedelta(days=10)).strftime("%Y-%m-%d %H:%M:%S.000")),
    promo("Spring Clearout", 30.0, False, (now - timedelta(days=60)).strftime("%Y-%m-%d %H:%M:%S.000"),
          (now - timedelta(days=11)).strftime("%Y-%m-%d %H:%M:%S.000")),
]
st, body = req("POST", "/api/_action/sync", {"p": {"entity": "promotion", "action": "upsert", "payload": promos}}, token)
print("promos sync:", st, json.dumps(body)[:400] if st >= 400 else "ok")

# Low stock
low = [{"id": p["id"], "stock": i + 1} for i, p in enumerate(products[:4])]
st, body = req("POST", "/api/_action/sync", {"s": {"entity": "product", "action": "upsert", "payload": low}}, token)
print("stock sync:", st, json.dumps(body)[:400] if st >= 400 else "ok")

print("done — connect the app with the admin login (admin/shopware)")
