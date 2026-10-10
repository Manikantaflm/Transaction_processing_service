package com.example.automation.steps;

import com.example.automation.DriverHooks;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/** Browser tests cover the operations landing page; financial correctness is verified at API/database layers. */
public class DashboardSteps {
    @Given("the transaction service landing page is available")
    public void landingPageAvailable() {
        String baseUrl = System.getProperty("baseUrl", "http://localhost:8080/");
        DriverHooks.driver().get(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
    }

    @Then("the page title should be {string}")
    public void title(String expected) {
        assertEquals(DriverHooks.driver().getTitle(), expected);
    }

    @Then("the service overview heading should be visible")
    public void heading() {
        WebElement heading = DriverHooks.driver().findElement(By.tagName("h1"));
        assertTrue(heading.isDisplayed());
        assertTrue(heading.getText().contains("Transaction Processing Service"));
    }

    @Then("the liveness endpoint link should be available")
    public void livenessLinkAvailable() {
        WebElement link = DriverHooks.driver().findElement(By.cssSelector("a[href='/health/live']"));
        assertTrue(link.isDisplayed());
        assertTrue(link.isEnabled());
    }

    @Then("the liveness endpoint should report healthy")
    public void livenessShouldBeHealthy() {
        DriverHooks.driver().findElement(By.cssSelector("a[href='/health/live']")).click();
        new WebDriverWait(DriverHooks.driver(), Duration.ofSeconds(5))
                .until(d -> d.findElement(By.tagName("body")).getText().contains("UP"));
        assertTrue(DriverHooks.driver().findElement(By.tagName("body")).getText().contains("UP"));
    }

    @Then("the API authentication notice should be visible")
    public void authenticationNoticeVisible() {
        assertTrue(DriverHooks.driver().findElement(By.tagName("body")).getText().contains("Use the API bearer token"));
    }
}
