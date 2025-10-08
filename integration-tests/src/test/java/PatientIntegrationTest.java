import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PatientIntegrationTest {
    @BeforeAll
    static void setup() {
        RestAssured.baseURI = "http://localhost:4004";
    }

    @Test
    public void shouldReturnPatientsWithValidToken() {
        String token = getToken();

        given()
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/api/patients")
                .then()
                .statusCode(200)
                .body("patients", notNullValue());
    }

    @Test
    public void shouldReturn429AfterLimitExceeded() throws InterruptedException {
        String token = getToken();

        int total = 10;
        int tooManyRequests = 0;

        for (int i = 0; i <= total; i++) {
            Response response = RestAssured
                    .given()
                    .header("Authorization", "Bearer " + token)
                    .get("/api/patients");

            System.out.printf("Request %d -> status: %d%n", i, response.statusCode());

            if (response.statusCode() == 429) {
                tooManyRequests++;
            }

            Thread.sleep(50);
        }

        assertTrue(tooManyRequests >= 1, "Expected at least one request to be rate limited 429");
    }


    private static String getToken() {
        // 1 arrange
        String loginPayload = """
                {
                    "email": "testuser@test.com",
                    "password": "password123"
                }
                """;

        // 2 act & assert
        String token = given()
                .contentType("application/json")
                .body(loginPayload)
                .when()
                .post("/auth/login")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .get("token");
        return token;
    }
}
