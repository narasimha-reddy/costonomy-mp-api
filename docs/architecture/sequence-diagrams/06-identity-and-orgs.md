# Architecture Sequence Diagrams: Identity, Restaurants & Suppliers Module

This document details the exact runtime request/response sequence diagrams for all endpoints in **Identity/Auth**, **Restaurant Management**, and **Supplier Onboarding/Verification**.

---

## 1. `POST /api/v1/auth/otp/request` & `POST /api/v1/auth/otp/verify`
**Description**: Passwordless mobile OTP authentication, JWT access/refresh token generation, and user role claims.

```mermaid
sequenceDiagram
    autonumber
    actor User as App User (Buyer/Seller)
    participant AC as AuthController
    participant AS as AuthService
    participant SMS as SMS / WhatsApp Provider
    participant JWT as JwtTokenService
    participant DB as PostgreSQL (app_user, user_role)

    User->>AC: POST /api/v1/auth/otp/request (phoneNumber)
    AC->>AS: requestOtp(phoneNumber)
    AS->>AS: Generate 6-digit cryptographically random OTP
    AS->>DB: Store hashed OTP with 5-minute TTL
    AS->>SMS: sendSms(phoneNumber, otpMessage)
    SMS-->>AS: 200 OK (Dispatched)
    AS-->>AC: OtpSentResponseDto
    AC-->>User: 200 OK (OTP Sent)

    User->>AC: POST /api/v1/auth/otp/verify (phoneNumber, otp)
    AC->>AS: verifyOtp(phoneNumber, otp)
    AS->>DB: Validate OTP hash and check expiry
    alt Invalid or Expired
        AS-->>AC: 401 Unauthorized
        AC-->>User: Error
    else Valid OTP
        AS->>DB: Find or create app_user by phone
        AS->>DB: Fetch associated organizations and permissions
        AS->>JWT: issueAccessToken(user, roles, permissions)
        AS->>JWT: issueRefreshToken(user)
        AS->>DB: Store refresh_token
        AS-->>AC: AuthTokensDto (accessToken, refreshToken, userProfile)
        AC-->>User: 200 OK
    end
```

---

## 2. `POST /api/v1/restaurants/{id}/outlets` (Outlet Provisioning)
**Description**: Restaurant organization adds a new physical outlet / delivery dock with geocoordinates and operating hours.

```mermaid
sequenceDiagram
    autonumber
    actor Owner as Restaurant Owner
    participant RC as RestaurantController
    participant OS as OutletService
    participant DB as PostgreSQL (outlet, outlet_address)

    Owner->>RC: POST /api/v1/restaurants/{id}/outlets (name, address, lat, lng, contact)
    RC->>OS: createOutlet(restaurantId, outletDto, principal)
    OS->>DB: Verify principal has RESTAURANT_ADMIN on restaurantId
    OS->>DB: INSERT INTO outlet (restaurant_id, name, contact_phone, is_active=true)
    OS->>DB: INSERT INTO outlet_address (outlet_id, street, city, postal_code, latitude, longitude)
    OS->>DB: Initialize empty wallet for outlet
    OS-->>RC: OutletDetailDto
    RC-->>Owner: 201 Created
```

---

## 3. `POST /api/v1/suppliers/{id}/verification` & Admin Approval
**Description**: Supplier business verification workflow (FSSAI license, GSTIN, bank documents) and Admin KYB activation.

```mermaid
sequenceDiagram
    autonumber
    actor Supplier as Supplier Owner
    actor Admin as Marketplace KYB Admin
    participant SC as SupplierController
    participant SVAC as SupplierVerificationAdminController
    participant SVS as SupplierVerificationService
    participant DB as PostgreSQL (supplier, supplier_verification)

    Supplier->>SC: POST /api/v1/suppliers/{id}/verification (gstin, fssaiNumber, docUrls)
    SC->>SVS: submitVerification(supplierId, verificationDto)
    SVS->>DB: INSERT INTO supplier_verification (supplier_id, gstin, fssai, status=PENDING_REVIEW)
    SVS->>DB: UPDATE supplier SET verification_status = UNDER_REVIEW
    SVS-->>SC: VerificationSubmittedDto
    SC-->>Supplier: 201 Created

    Admin->>SVAC: POST /api/v1/admin/suppliers/verifications/{id}/review (decision=APPROVED)
    SVAC->>SVS: reviewVerification(id, decision, notes, adminPrincipal)
    SVS->>DB: UPDATE supplier_verification SET status = APPROVED, reviewed_by = admin.id
    SVS->>DB: UPDATE supplier SET verification_status = VERIFIED, is_active = true
    SVS-->>SVAC: ReviewResultDto
    SVAC-->>Admin: 200 OK (Supplier Activated on Marketplace)
```
