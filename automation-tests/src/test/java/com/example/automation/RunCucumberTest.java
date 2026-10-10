package com.example.automation;
import io.cucumber.testng.AbstractTestNGCucumberTests; import io.cucumber.testng.CucumberOptions;
@CucumberOptions(features="src/test/resources/features",glue="com.example.automation",plugin={"pretty","html:target/cucumber-report.html","json:target/cucumber-report.json"},monochrome=true)
public class RunCucumberTest extends AbstractTestNGCucumberTests {}