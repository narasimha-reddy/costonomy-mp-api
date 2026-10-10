# Architecture Sequence Diagrams: Realtime, Chat, Notifications & Administration Module

This document details the exact runtime request/response sequence diagrams for all endpoints in **Realtime Streams (SSE)**, **In-App Chat**, **Notification Feeds**, **Media Storage**, and **Operations Admin**.

---

## 1. `POST /api/v1/realtime/ticket` & `GET /api/v1/realtime/events` (SSE Streaming)
**Description**: Authenticated ticket-based Server-Sent Events (SSE) subscription stream for live orders, chat messages, and driver locations.

```mermaid
sequenceDiagram
    autonumber
    actor Client as Mobile / Web Client
    participant RC as RealtimeController
    participant RS as RealtimeService
    participant RSH as RealtimeSessionHub
    participant JWT as JwtTokenService

    Client->>RC: POST /api/v1/realtime/ticket (Headers: Bearer JWT)
    RC->>RS: generateOneTimeTicket(principal)
    RS->>RS: Generate 60-second cryptographically random ticket
    RS-->>RC: RealtimeTicketDto(ticket)
    RC-->>Client: 200 OK (ticket)

    Client->>RC: GET /api/v1/realtime/events?ticket=...
    RC->>RS: validateAndConsumeTicket(ticket)
    RS-->>RC: Valid Principal Details
    RC->>RSH: registerSubscriber(principal.userId, SseEmitter)
    RC-->>Client: 200 OK (Content-Type: text/event-stream)
    
    loop On Domain Event (e.g. Order status changed, new chat message)
        RSH->>Client: event: order_updated\ndata: {"orderId": 42, "status": "OUT_FOR_DELIVERY"}\n\n
    end
```

---

## 2. `POST /api/v1/chats/{id}/messages` & Live Delivery
**Description**: Buyer-seller direct messaging regarding order specifications, delivery issues, or substitutes.

```mermaid
sequenceDiagram
    autonumber
    actor Sender as Buyer / Seller
    participant CC as ChatController
    participant CS as ChatService
    participant RSH as RealtimeSessionHub
    participant NR as NotificationRelay
    participant DB as PostgreSQL (chat_thread, chat_message)

    Sender->>CC: POST /api/v1/chats/{id}/messages (text, attachmentUrl)
    CC->>CS: postMessage(threadId, messageDto, principal)
    CS->>DB: Verify principal is participant of thread
    CS->>DB: INSERT INTO chat_message (thread_id, sender_id, text, attachment_url)
    CS->>DB: UPDATE chat_thread SET last_message_at = NOW()
    
    par Real-Time Broadcast
        CS->>RSH: broadcastEvent(recipientUserId, 'new_chat_message', messageDto)
    and Push / SMS Fallback
        CS->>NR: sendPushNotificationIfOffline(recipientUserId, previewText)
    end
    
    CS-->>CC: ChatMessageDto
    CC-->>Sender: 201 Created
```

---

## 3. `GET /api/v1/notifications` & `POST /api/v1/notifications/{id}/read`
**Description**: Querying and acknowledging the user's persistent notification inbox feed.

```mermaid
sequenceDiagram
    autonumber
    actor User as App User
    participant NC as NotificationController
    participant NS as NotificationService
    participant DB as PostgreSQL (notification)

    User->>NC: GET /api/v1/notifications?page=0&size=20
    NC->>NS: listUserNotifications(principal.userId, pageable)
    NS->>DB: SELECT * FROM notification WHERE user_id = principal.userId ORDER BY created_at DESC
    NS-->>NC: PagedResponse of NotificationDto (title, body, category, isRead)
    NC-->>User: 200 OK

    User->>NC: POST /api/v1/notifications/{id}/read
    NC->>NS: markAsRead(id, principal.userId)
    NS->>DB: UPDATE notification SET is_read = true WHERE id = id AND user_id = principal.userId
    NS-->>NC: 200 OK
    NC-->>User: 200 OK
```

---

## 4. `POST /api/v1/upload` (Document & Image Media Storage)
**Description**: Multi-part file upload for dispute evidence, product images, and supplier verification licenses.

```mermaid
sequenceDiagram
    autonumber
    actor User as Client
    participant UC as UploadController
    participant SS as StorageService
    participant FS as Local Filesystem / S3 Bucket
    participant DB as PostgreSQL (stored_file)

    User->>UC: POST /api/v1/upload (multipart/form-data: file, category)
    UC->>SS: storeFile(file, category, principal)
    SS->>SS: Validate MIME type, file extension, and size limits (< 10MB)
    SS->>SS: Generate unique SHA-256 content key
    SS->>FS: Write bytes to /files/{category}/{uuid}.ext
    SS->>DB: INSERT INTO stored_file (key, original_name, mime_type, file_size, url)
    SS-->>UC: FileUploadResponseDto(url, key)
    UC-->>User: 201 Created (public access URL)
```

---

## 5. `GET /api/v1/admin/refunds/failures` & `/manual-override`
**Description**: Operations console monitoring for failed automated refunds or withdrawals, with audited manual resolution actions.

```mermaid
sequenceDiagram
    autonumber
    actor Admin as Operations Auditor
    participant ARC as AdminRefundController
    participant WRS as WithdrawalReversalService
    participant DB as PostgreSQL (refund, wallet_transaction, audit_log)

    Admin->>ARC: GET /api/v1/admin/refunds/failures?page=0&size=20
    ARC->>WRS: listFailedRefundsAndWithdrawals(pageable)
    WRS->>DB: SELECT * FROM refund WHERE status = 'MANUAL_REVIEW_REQUIRED' ORDER BY updated_at DESC
    WRS-->>ARC: PagedResponse of FailedRefundAuditDto
    ARC-->>Admin: 200 OK

    Admin->>ARC: POST /api/v1/admin/refunds/{id}/manual-override (decision: 'FORCE_RESOLVE', justification)
    ARC->>WRS: applyManualOverride(id, decision, justification, adminPrincipal)
    WRS->>DB: SELECT * FROM refund WHERE id = id FOR UPDATE
    WRS->>DB: UPDATE refund SET status = RESOLVED_MANUALLY, resolved_by = admin.id
    WRS->>DB: INSERT INTO audit_log (entity_type='REFUND', entity_id=id, action='MANUAL_OVERRIDE', notes=justification)
    WRS-->>ARC: ManualOverrideResultDto
    ARC-->>Admin: 200 OK
```
