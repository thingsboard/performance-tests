/**
 * Copyright © 2016-2018 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.tools.service.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.thingsboard.client.tools.RestClient;
import org.thingsboard.server.common.data.id.RuleChainId;
import org.thingsboard.server.common.data.page.TextPageData;
import org.thingsboard.server.common.data.rule.RuleChain;
import org.thingsboard.server.common.data.rule.RuleChainMetaData;

import java.util.Optional;

@Slf4j
@Service
public class RuleChainManager {

    @Value("${rest.url}")
    private String restUrl;

    @Value("${rest.username}")
    private String username;

    @Value("${rest.password}")
    private String password;

    private RestClient restClient;
    private RuleChainId defaultRootRuleChainId;
    private RuleChainId updatedRuleChainId;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public void createRuleChainWithCountNodeAndSetAsRoot() {
        restClient = new RestClient(restUrl);
        restClient.login(username, password);

        defaultRootRuleChainId = getDefaultRuleChainId();

        try {
            JsonNode updatedRootRuleChainConfig = objectMapper.readTree(this.getClass().getClassLoader().getResourceAsStream("root_rule_chain.json"));
            RuleChain ruleChain = objectMapper.treeToValue(updatedRootRuleChainConfig.get("ruleChain"), RuleChain.class);
            updatedRuleChainId = saveUpdatedRootRuleChainAndSetAsRoot(ruleChain);
            setRootRuleChain(updatedRuleChainId);

            RuleChainMetaData ruleChainMetaData = objectMapper.treeToValue(updatedRootRuleChainConfig.get("metadata"), RuleChainMetaData.class);
            ruleChainMetaData.setRuleChainId(updatedRuleChainId);
            saveUpdatedRootRuleChainMetadata(ruleChainMetaData);

        } catch (Exception e) {
            log.error("Exception during creation of root rule chain", e);
        }
    }

    public void revertRootNodeAndCleanUp() {
        setRootRuleChain(defaultRootRuleChainId);
        restClient.getRestTemplate().delete(restUrl + "/api/ruleChain/" + updatedRuleChainId.getId());
    }

    private void setRootRuleChain(RuleChainId rootRuleChain) {
        restClient.getRestTemplate()
                .postForEntity(restUrl + "/api/ruleChain/" + rootRuleChain.getId() + "/root", null, RuleChain.class);
    }

    private RuleChainId saveUpdatedRootRuleChainAndSetAsRoot(RuleChain updatedRuleChain) {
        ResponseEntity<RuleChain> ruleChainResponse = restClient.getRestTemplate()
                .postForEntity(restUrl + "/api/ruleChain", updatedRuleChain, RuleChain.class);

        return ruleChainResponse.getBody().getId();
    }

    private void saveUpdatedRootRuleChainMetadata(RuleChainMetaData ruleChainMetaData) {
        restClient.getRestTemplate()
                .postForEntity(restUrl + "/api/ruleChain/metadata",
                        ruleChainMetaData,
                        RuleChainMetaData.class);
    }

    private RuleChainId getDefaultRuleChainId() {
        ResponseEntity<TextPageData<RuleChain>> ruleChains =
                restClient.getRestTemplate().exchange(
                        restUrl + "/api/ruleChains?limit=999&textSearch=",
                        HttpMethod.GET,
                        null,
                        new ParameterizedTypeReference<TextPageData<RuleChain>>() {
                        });

        Optional<RuleChain> defaultRuleChain = ruleChains.getBody().getData()
                .stream()
                .filter(RuleChain::isRoot)
                .findFirst();

        if (!defaultRuleChain.isPresent()) {
            throw new RuntimeException("Root rule chain was not found");
        }

        return defaultRuleChain.get().getId();
    }
}
