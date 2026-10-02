# Architecture Sequence Diagrams: Discovery & Catalog Module

This document details the exact runtime request/response sequence diagrams for all endpoints in the **Discovery, Search, and Catalog Management** modules.

---

## 1. `GET /api/v1/search/skus` & `GET /api/v1/search/suppliers`
**Description**: Geospatial and relevance search for wholesale SKUs and suppliers within the restaurant outlet's delivery radius.

```mermaid
sequenceDiagram
    autonumber
    actor Buyer as Restaurant Buyer
    participant DC as DiscoveryController
    participant DS as DiscoveryService
    participant DB as PostgreSQL (sku, store, address)

    Buyer->>DC: GET /api/v1/search/skus?q=onion&outletId=101&page=0&size=20
    DC->>DS: searchSkus(query, outletId, pageable)
    DS->>DB: Fetch outlet delivery coordinates (lat, lng)
    DS->>DB: Query supplier_sku s JOIN supplier_store ss ON s.store_id = ss.id WHERE s.name ILIKE '%onion%' AND ST_DWithin(ss.geom, outlet.geom, ss.delivery_radius_km)
    DS->>DS: Rank by price, MOQ, supplier rating, and delivery distance
    DS-->>DC: PagedResponse of SkuSearchResultDto
    DC-->>Buyer: 200 OK
```

---

## 2. `GET /api/v1/supplier-stores/{storeId}/storefront`
**Description**: Fetches supplier digital storefront banner, minimum order quantity (MOQ), operating schedules, and product catalog categories.

```mermaid
sequenceDiagram
    autonumber
    actor Buyer as Restaurant Buyer
    participant DC as DiscoveryController
    participant DS as DiscoveryService
    participant DB as PostgreSQL (supplier_store, category, rating)

    Buyer->>DC: GET /api/v1/supplier-stores/{storeId}/storefront
    DC->>DS: getStorefront(storeId)
    DS->>DB: SELECT * FROM supplier_store WHERE id = storeId
    DS->>DB: SELECT AVG(score), COUNT(*) FROM rating WHERE supplier_store_id = storeId
    DS->>DB: SELECT DISTINCT c.* FROM category c JOIN product p ON ... JOIN supplier_sku s ON ... WHERE s.store_id = storeId
    DS-->>DC: StorefrontDetailDto
    DC-->>Buyer: 200 OK
```

---

## 3. `POST /api/v1/supplier-stores/{storeId}/skus` & `/bulk`
**Description**: Supplier publishes or bulk updates inventory SKUs, base unit prices, GST rates, pack sizing, and availability.

```mermaid
sequenceDiagram
    autonumber
    actor Supplier as Supplier Seller
    participant SCC as SupplierCatalogController
    participant SCS as SupplierCatalogService
    participant DB as PostgreSQL (supplier_sku, sku_inventory)

    Supplier->>SCC: POST /api/v1/supplier-stores/{storeId}/skus (productId, price, unit, packSize, gstRate)
    SCC->>SCS: createSku(storeId, skuDto, principal)
    SCS->>DB: Verify principal has SUPPLIER_CATALOG permission
    SCS->>DB: INSERT INTO supplier_sku (store_id, product_id, base_price, pack_size, gst_rate, in_stock=true)
    SCS-->>SCC: SkuDetailDto
    SCC-->>Supplier: 201 Created

    Supplier->>SCC: POST /api/v1/supplier-stores/{storeId}/skus/bulk (List of SKU updates)
    SCC->>SCS: bulkUpsertSkus(storeId, skuList, principal)
    SCS->>DB: Batch INSERT ... ON CONFLICT (store_id, product_id) DO UPDATE SET base_price=..., in_stock=...
    SCS-->>SCC: BulkOperationResultDto (updatedCount, insertedCount)
    SCC-->>Supplier: 200 OK
```
