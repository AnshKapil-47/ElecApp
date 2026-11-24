# Test base and fixture builder (sketch)

```java
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Testcontainers
abstract class IntegrationTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
    // Flyway runs V1..Vn on startup; ddl-auto=validate checks the entities.

    @Autowired protected TestRestTemplate http;
    @Autowired protected Fixtures fx;          // builds the fixture world below
    @MockitoBean protected Clock clock;        // control time
}

@Component
class Fixtures {
    // Insert rows with JdbcTemplate (fast, explicit), then log in through the real auth endpoint
    // to get tokens, so session rules are exercised.
    World world() {
        long owner = user("owner", "OWNER", null);
        long agentA = user("agentA", "AGENT", null), agentB = user("agentB", "AGENT", null);
        long c1 = customer("+919800000001", agentA), c2 = customer("+919800000002", agentB);
        long c1Admin = user("ca1", "CUSTOMER_ADMIN", c1), s1 = user("su1", "SUBUSER", c1);
        long c2Admin = user("ca2", "CUSTOMER_ADMIN", c2), s2 = user("su2", "SUBUSER", c2);
        long ds = masterDataset("HARYANA", 10, List.of(101, 102));   // partitions + synthetic voters
        long e1 = entitlement(c1, "VIDHAN_SABHA", ds, Map.of("state","HARYANA","ac_no",10), now(), now().plus(10, DAYS));
        long e2 = entitlement(c2, "VIDHAN_SABHA", ds, Map.of("state","HARYANA","ac_no",10), now(), now().plus(10, DAYS));
        long e1x = entitlement(c1, "LOK_SABHA", ds, Map.of("state","HARYANA","pc_no",1,"ac_no",10), now().minus(20, DAYS), now().minus(10, DAYS));
        booth(c1, s1, e1, 101, now(), now().plus(7, DAYS));
        return new World(owner, agentA, agentB, c1, c2, c1Admin, s1, c2Admin, s2, ds, e1, e2, e1x);
    }
}
```

Example test:

```java
@Test
void customer_cannot_read_other_customers_entitlement() {
    var w = fx.world();
    var token = login("ca1");
    var res = http.exchange("/api/app/entitlements/" + w.e2() + "/voters", GET, auth(token), String.class);
    assertThat(res.getStatusCode()).isEqualTo(NOT_FOUND);
    assertThat(res.getBody()).doesNotContain("HARYANA").doesNotContainIgnoringCase("dataset");
}
```
