package com.costonomy.mp.chat.service;

import com.costonomy.mp.chat.domain.ChatAttachmentType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * What chat needs to know about the two parties, without reaching into their
 * modules' repositories.
 *
 * <p>Reads, by SQL, the way every other module's directory does here. Nothing in
 * this class writes, and nothing it returns is authoritative anywhere else.
 */
@Service
@RequiredArgsConstructor
public class ChatDirectory {

    private final JdbcTemplate jdbc;

    /**
     * The two ends of a conversation.
     *
     * @param traded whether these two have ever had a request or an order
     *               between them — the condition for a thread existing at all
     */
    public record Pair(
            Long outletId,
            String outletName,
            String restaurantName,
            boolean outletChatEnabled,
            Long supplierStoreId,
            String storeName,
            String supplierName,
            boolean storeChatEnabled,
            boolean traded) {

        /** Both ends have to be on. Either side off closes the conversation. */
        public boolean chatEnabled() {
            return outletChatEnabled && storeChatEnabled;
        }
    }

    public Optional<Pair> pair(Long outletId, Long supplierStoreId) {
        var rows = jdbc.query("""
                select o.id, o.name, r.name, o.chat_enabled,
                       s.id, s.name, org.display_name, s.chat_enabled,
                       exists (
                           select 1 from intent i
                            where i.outlet_id = o.id and i.supplier_store_id = s.id
                              and i.status <> 'DRAFT')
                       or exists (
                           select 1 from supplier_order so
                            where so.outlet_id = o.id and so.supplier_store_id = s.id)
                  from outlet o
                  join restaurant r on r.id = o.restaurant_id
                  join supplier_store s on s.id = ?
                  join supplier_organization org on org.id = s.supplier_organization_id
                 where o.id = ?
                """,
                (rs, i) -> new Pair(
                        rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4),
                        rs.getLong(5), rs.getString(6), rs.getString(7), rs.getBoolean(8),
                        rs.getBoolean(9)),
                supplierStoreId, outletId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Requests and orders these two have between them, newest first.
     *
     * <p>What the share picker offers. Scoped to the pair rather than to the
     * sender's whole history, because the point of sharing a link here is that
     * the other side can open it — and they can only open their own.
     *
     * <p>Drafts are excluded: a draft request is a basket nobody has seen, and
     * sharing a link to one would send the supplier somewhere they get a 404.
     */
    public List<Shareable> shareables(Long outletId, Long supplierStoreId, int limit) {
        return jdbc.query("""
                select 'ORDER' as kind, so.id, so.order_number, so.status, so.created_at
                  from supplier_order so
                 where so.outlet_id = ? and so.supplier_store_id = ?
                union all
                select 'REQUEST', i.id, i.reference, i.status, i.created_at
                  from intent i
                 where i.outlet_id = ? and i.supplier_store_id = ? and i.status <> 'DRAFT'
                 order by created_at desc
                 limit ?
                """,
                (rs, i) -> new Shareable(
                        ChatAttachmentType.valueOf(rs.getString(1)),
                        rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5).toInstant()),
                outletId, supplierStoreId, outletId, supplierStoreId, limit);
    }

    /**
     * Whether one shareable really belongs to this pair.
     *
     * <p>Checked on send, not trusted from the client. An attachment is a link
     * the other side will tap, and an id from somebody else's order would be a
     * link into a tenant they cannot see — a 404 at best, and at worst a probe
     * for which ids exist.
     */
    public Optional<String> attachmentReference(
            Long outletId, Long supplierStoreId, ChatAttachmentType type, Long id) {

        String sql = type == ChatAttachmentType.ORDER
                ? """
                  select so.order_number from supplier_order so
                   where so.id = ? and so.outlet_id = ? and so.supplier_store_id = ?
                  """
                : """
                  select i.reference from intent i
                   where i.id = ? and i.outlet_id = ? and i.supplier_store_id = ?
                     and i.status <> 'DRAFT'
                  """;
        var rows = jdbc.queryForList(sql, String.class, id, outletId, supplierStoreId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public record Shareable(
            ChatAttachmentType type,
            Long id,
            String reference,
            String status,
            Instant createdAt) {
    }
}
