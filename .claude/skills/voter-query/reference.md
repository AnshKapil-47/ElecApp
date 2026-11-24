# Voter query: worked example (Spring, JdbcTemplate / NamedParameterJdbcTemplate)

```java
public record FieldDef(String sourceColumn, String valueType, String usage, Short filterOrder, short displayOrder) {}

@Service
@RequiredArgsConstructor
class VoterQueryService {
    private final NamedParameterJdbcTemplate jdbc;
    private final EntitlementRepository entitlements;     // scoped finder below
    private final FieldCatalog fields;                     // cached election_type_field rows (read-only config)
    private final BoothAssignmentRepository booths;
    private final Clock clock;

    Page<VoterRow> search(CurrentUser me, long entitlementId, String q, int page, int size) {
        Instant now = clock.instant();
        // 1. entitlement gate: id AND tenant AND validity, in ONE query
        Entitlement e = entitlements.findUsable(entitlementId, me.customerAccountId(), now)
                .orElseThrow(NotFound::new);                       // 404, never 403
        ElectionTypeConfig cfg = fields.forType(e.getElectionTypeId());

        // 2. columns: whitelisted names from config only, quoted as identifiers
        String cols = cfg.visibleColumns().stream()                // FILTER + DISPLAY in display_order
                .filter(c -> !c.equals("mobile_no") && !c.equals("voter_image"))
                .map(c -> "v." + quoteIdent(c))
                .collect(joining(", "));

        // 3. area predicate: one bind param per FILTER field, values from the entitlement row
        MapSqlParameterSource p = new MapSqlParameterSource("datasetId", e.getDatasetId());
        StringBuilder where = new StringBuilder("v.dataset_id = :datasetId");
        int i = 0;
        for (FieldDef f : cfg.filters()) {                         // filter_order
            String name = "f" + (i++);
            where.append(" AND v.").append(quoteIdent(f.sourceColumn())).append(" = :").append(name);
            p.addValue(name, typed(e.getCriteria().get(f.sourceColumn()), f.valueType()));
        }

        // 4. Subuser booth scope
        if (me.isSubuser()) {
            List<Integer> bs = booths.activeBoothValues(me.userId(), e.getId(), now);
            if (bs.isEmpty()) throw new NotFound();
            where.append(" AND v.").append(quoteIdent(cfg.boothColumn())).append(" IN (:booths)");
            p.addValue("booths", bs);
        }

        // search: name (trigram) or EPIC; bound, never concatenated
        if (q != null && !q.isBlank()) {
            where.append(" AND ((coalesce(v.v_fname_en,'') || ' ' || coalesce(v.v_lname_en,'')) ILIKE :q")
                 .append(" OR (coalesce(v.v_fname_mt,'') || ' ' || coalesce(v.v_lname_mt,'')) ILIKE :q")
                 .append(" OR v.voter_id = :epic)");
            p.addValue("q", "%" + escapeLike(q.trim()) + "%").addValue("epic", q.trim().toUpperCase());
        }

        String sql = "SELECT v.id AS voter_ref, " + cols
                   + (canSeeMobile(me, e) ? ", v.mobile_no_enc, v.mobile_key_version" : "")
                   + " FROM voter v WHERE " + where
                   + " ORDER BY v.part_no, v.serial_no LIMIT :limit OFFSET :offset";
        p.addValue("limit", Math.min(size, 100)).addValue("offset", (long) page * Math.min(size, 100));
        // 5. map rows: decrypt mobile only when canSeeMobile(...) was true; never put dataset_id in the DTO
        return mapPage(jdbc.queryForList(sql, p), cfg, canSeeMobile(me, e));
    }

    private boolean canSeeMobile(CurrentUser me, Entitlement e) {
        return props.mobileNumbersEnabled() && me.isCustomerAdmin() && e.isMobileAccess();
    }

    /** Defence in depth: config is already DB-whitelisted; still refuse anything not a plain identifier. */
    private static String quoteIdent(String c) {
        if (!c.matches("[a-z][a-z0-9_]{0,62}")) throw new IllegalStateException("bad column " + c);
        return "\"" + c + "\"";
    }
}
```

```java
// Scoped entitlement finder (Spring Data JPA)
@Query("""
   select e from Entitlement e join e.customerAccount c
   where e.id = :id and c.id = :customerId and c.status = 'ACTIVE'
     and e.status = 'ACTIVE' and e.validFrom <= :now and e.validUntil > :now
""")
Optional<Entitlement> findUsable(long id, long customerId, Instant now);
```

Notes:
- `FieldCatalog` may cache `election_type_field` (it only changes by migration). Never cache entitlement or booth decisions.
- Count queries for paging reuse the same WHERE. For large areas, prefer keyset pagination `(part_no, serial_no, id) > (...)`.
- EXPLAIN a search on a full-state dataset once: it must show a single partition (`voter_d<id>`) and an index scan on the lens's filter index.
