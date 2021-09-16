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
import org.thingsboard.rest.client.RestClient;
import org.thingsboard.server.common.data.id.IdBased;
import org.thingsboard.server.common.data.id.RuleChainId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.rule.RuleChain;
import org.thingsboard.server.common.data.rule.RuleChainMetaData;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RuleChainManager {

    @Value("${rest.url}")
    private String restUrl;

    @Value("${rest.username}")
    private String username;

    @Value("${rest.password}")
    private String password;

    @Value("${test.ruleChainName:root_rule_chain_pe.json}")
    private String ruleChainName;


    private RestClient restClient;
    private RuleChainId defaultRootRuleChainId;
    private RuleChainId updatedRuleChainId;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public void createRuleChainWithCountNodeAndSetAsRoot() {
        restClient = new RestClient(restUrl);
        restClient.login(username, password);

        defaultRootRuleChainId = getDefaultRuleChainId();

        try {
            JsonNode updatedRootRuleChainConfig = objectMapper.readTree(this.getClass().getClassLoader().getResourceAsStream(ruleChainName));
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
        restClient.login(username, password);
        setRootRuleChain(defaultRootRuleChainId);
        restClient.deleteRuleChain(updatedRuleChainId);
    }

    private void setRootRuleChain(RuleChainId rootRuleChain) {
        restClient.setRootRuleChain(rootRuleChain);
    }

    private RuleChainId saveUpdatedRootRuleChainAndSetAsRoot(RuleChain updatedRuleChain) {
        return restClient.saveRuleChain(updatedRuleChain).getId();
    }

    private void saveUpdatedRootRuleChainMetadata(RuleChainMetaData ruleChainMetaData) {
        restClient.saveRuleChainMetaData(ruleChainMetaData);
    }

    private RuleChainId getDefaultRuleChainId() {
        PageData<RuleChain> ruleChains =
                restClient.getRuleChains(new PageLink(999, 0, "Root Rule Chain"));

        Optional<RuleChain> defaultRuleChain = ruleChains.getData()
                .stream()
                .findFirst();

        if (defaultRuleChain.isEmpty()) {
            throw new RuntimeException("Root rule chain was not found");
        }

        setRootRuleChain(defaultRuleChain.get().getId());
        return defaultRuleChain.get().getId();
    }
}
