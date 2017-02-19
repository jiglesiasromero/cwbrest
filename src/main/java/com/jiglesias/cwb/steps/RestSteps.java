package com.jiglesias.cwb.steps;

import cucumber.api.DataTable;
import cucumber.api.java.en.And;
import cucumber.api.java.en.Given;
import cucumber.api.java.en.Then;
import org.apache.commons.lang3.StringUtils;
import org.junit.Assert;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.expression.AccessException;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.PropertyAccessor;
import org.springframework.expression.TypedValue;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.yaml.snakeyaml.Yaml;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

public class RestSteps {

    private static final String HTTP_METHODS = "get|post|put|head|delete|options|patch|trace|GET|POST|PUT|HEAD|DELETE|OPTIONS|PATCH|TRACE";
    private static final String COUNT_COMPARISON = "(?: (less than|more than|at least|at most))?";
    private final StandardEvaluationContext spelCtx;

    private List<Map> bodyListMap;
    private String processInstanceId;

    private ResponseEntity<Object> response;
    private SpelExpressionParser parser;
    private HttpHeaders headers;

    @Autowired
    protected RestTemplate restTemplate;

    public RestSteps() {
        parser = new SpelExpressionParser();
        spelCtx = new StandardEvaluationContext();
        spelCtx.addPropertyAccessor(new MapAsPropertyAccessor());
        restTemplate = new RestTemplate();
    }

    @Given("^I call (" + HTTP_METHODS + ") \"([^\"]*)\"$")
    public void iCall(String httpMethodString, String path) throws Throwable {
        call(httpMethodString, path);
    }

    @Given("^I call (" + HTTP_METHODS + ") \"([^\"]*)\" with data(?:[:])?$")
    public void iCallWithData(String httpMethodString, String path, String data) throws Throwable {
        Yaml yaml = new Yaml();
        Object obj = yaml.load(data);
        call(httpMethodString, path, obj);
    }

    @And("^I set headers to:$")
    public void iSetHeadersTo(DataTable headersTable) throws Throwable {
        headers = new HttpHeaders();
        headersTable.getGherkinRows().forEach(r -> headers.set(r.getCells().get(0), r.getCells().get(1)));
    }

    private void call(String httpMethodString, String path) {
        call(httpMethodString, path, null);
    }

    private void call(String httpMethodString, String path, Object requestObj) {
        HttpMethod httpMethod = HttpMethod.valueOf(StringUtils.upperCase(httpMethodString));
        if (httpMethod.equals(HttpMethod.GET) && requestObj != null) {
            throw new IllegalArgumentException("You can't pass data in a GET call");
        }

        HttpEntity<?> request = new HttpEntity<>(requestObj, this.headers);

        Pattern pattern = Pattern.compile("\\$\\{response.(.*)}");
        Matcher matcher = pattern.matcher(path);
        if (matcher.find()) {
            String value = matcher.group(1);
            path = matcher.replaceFirst((String) parser.parseRaw(value).getValue(spelCtx, response.getBody()));
        }

        response = restTemplate.exchange(path, httpMethod, request, Object.class);
    }

    @Then("^The response status should be (\\d+)$")
    public void theResponseStatusShouldBe(int status) throws Throwable {
        assertEquals(status, response.getStatusCode().value());
    }

    @And("^The response should contains empty array$")
    public void theResponseShouldContainsEmptyArray() {
        assertResponseListSize(0);
    }

    @And("^The response size is (\\d+)$")
    public void theResponseSizeIs(int size) throws Throwable {
        assertResponseListSize(size);
    }

    private void assertResponseListSize(int size) {
        Object body = response.getBody();
        Assert.assertNotNull(body);
        assertTrue(List.class.isAssignableFrom(body.getClass()));
        assertEquals(size, ((List) body).size());
    }

    @And("^The response entity should contains \"([^\"]*)\"$")
    public void theResponseEntityShouldContains(String key) throws Throwable {
        assertResponseContainsKey(key);
    }

    @And("^The response entity should not contains \"([^\"]*)\"$")
    public void theResponseEntityShouldNotContains(String key) throws Throwable {
        assertResponseNotContainsKey(key);
    }

    @And("^The response entity should contains \"([^\"]*)\" with value \"([^\"]*)\"$")
    public void theResponseEntityShouldContainsWithValue(String key, String value) throws Throwable {
        Map bodyMap = assertResponseContainsKey(key);
        assertEquals(value, bodyMap.get(key));
    }

    @And("^The response entity should contains \"([^\"]*)\" with value ([^\"]*)$")
    public void theResponseEntityShouldContainsWithValue(String key, Integer value) throws Throwable {
        Map bodyMap = assertResponseContainsKey(key);
        assertEquals(value, bodyMap.get(key));
    }

    @And("^The response entity \"([^\"]*)\" should contain(?:s)? " + COUNT_COMPARISON + "(\\d+) entit(?:ies|y)$")
    public Object theResponseEntityContainEntities(String entityName, String comparisonAction, int childCount) {
        Map bodyMap = assertResponseContainsKey(entityName);
        Object entity = bodyMap.get(entityName);
        assertNotNull(entity);
        assertTrue(entity instanceof Collection);
        compareCounts(comparisonAction, childCount, ((Collection) entity).size());
        return entity;
    }

    @And("^The response entity \"([^\"]*)\" should contain(?:s)? \"([^\"]*)\"$")
    public Object theResponseEntityContain(String entity, String key) {
        Map bodyMap = assertResponseIsMap();
        Object object = parser.parseRaw(entity).getValue(spelCtx, bodyMap);
        assertTrue(object instanceof Map);
        Object value = ((Map) object).get(key);
        assertNotNull(value);
        return value;
    }

    @And("^The response entity \"([^\"]*)\" should contain(?:s)? \"([^\"]*)\" with value \"([^\"]*)\"$")
    public void theResponseEntityContainKeyWithValueString(String entity, String key, String value) {
        Object entityValue = theResponseEntityContain(entity, key);
        assertEquals(value, entityValue);
    }

    @And("^The response entity \"([^\"]*)\" should contain(?:s)? \"([^\"]*)\" with value ([^\"]*)$")
    public void theResponseEntityContainKeyWithValueInt(String entity, String key, Integer value) {
        Object entityValue = theResponseEntityContain(entity, key);
        assertEquals(value, entityValue);
    }

    private void compareCounts(String comparison, int expected, int actual) {
        if (StringUtils.equals("at least", comparison)) {
            assertTrue(actual >= expected);
        } else if (StringUtils.equals("at most", comparison)) {
            assertTrue(actual <= expected);
        } else if (StringUtils.equals("more than", comparison)) {
            assertTrue(actual > expected);
        } else if (StringUtils.equals("less than", comparison)) {
            assertTrue(actual < expected);
        } else {
            assertEquals(expected, actual);
        }
    }

    private Map assertResponseIsMap() {
        Object body = response.getBody();
        Assert.assertNotNull(body);
        assertTrue(Map.class.isAssignableFrom(body.getClass()));
        return (Map) response.getBody();
    }

    private Map assertResponseContainsKey(String key) {
        return assertResponseContainsOrNotKey(key, true);
    }

    private Map assertResponseNotContainsKey(String key) {
        return assertResponseContainsOrNotKey(key, false);
    }

    private Map assertResponseContainsOrNotKey(String key, boolean shouldContains) {
        Map bodyMap = assertResponseIsMap();
        boolean containsKey = bodyMap.containsKey(key);
        assertEquals(containsKey, shouldContains);
        return bodyMap;
    }

    @And("^The response is empty$")
    public void theResponseIsEmpty() throws Throwable {
        Assert.assertNull(response.getBody());
    }

    @Given("^I call (" + HTTP_METHODS + ") \"([^\"]*)\" with file \"([^\"]*)\" from \"([^\"]*)\"$")
    public void iCallWithFileFrom(String httpMethodString, String path, String file, String from) throws Throwable {
        FileSystemResource fileSystemResource = new FileSystemResource(new File(RestSteps.class.getResource("/").getPath() + from));
        callWithFile(httpMethodString, path, file, fileSystemResource);
    }

    @Given("^I call (" + HTTP_METHODS + ") \"([^\"]*)\" with (null|empty) file \"([^\"]*)\"$")
    public void iCallWithFileNull(String httpMethodString, String path, String type, String file) throws Throwable {
        callWithFile(httpMethodString, path, file, type.equals("null") ? null : new InputStreamResource(new ByteArrayInputStream(new byte[]{})));
    }

    private void callWithFile(String httpMethodString, String path, String file, Resource resource) {
        HttpMethod httpMethod = HttpMethod.valueOf(StringUtils.upperCase(httpMethodString));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        LinkedMultiValueMap<String, Object> map = new LinkedMultiValueMap<>();
        map.add(file, resource);
        HttpEntity<LinkedMultiValueMap<String, Object>> request = new HttpEntity<>(map, headers);
        response = restTemplate.exchange(path, httpMethod, request, Object.class);
    }

    public static class MapAsPropertyAccessor implements PropertyAccessor {
        @Override
        public Class<?>[] getSpecificTargetClasses() {
            return null;
        }

        @Override
        public boolean canRead(EvaluationContext evaluationContext, Object o, String s) throws AccessException {
            return o instanceof Map && ((Map) o).containsKey(s);
        }

        @Override
        public TypedValue read(EvaluationContext evaluationContext, Object o, String s) throws AccessException {
            return new TypedValue(((Map) o).get(s));
        }

        @Override
        public boolean canWrite(EvaluationContext evaluationContext, Object o, String s) throws AccessException {
            return false;
        }

        @Override
        public void write(EvaluationContext evaluationContext, Object o, String s, Object o2) throws AccessException {

        }
    }
}
