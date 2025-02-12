package com.zjs.feishubot.util.chatgpt;

import com.zjs.feishubot.entity.Account;
import com.zjs.feishubot.entity.AccountList;
import org.springframework.core.io.FileSystemResource;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.representer.Representer;

import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public class AccountUtil {
    private static final String YAML_PATH = "accounts.yaml";

    public static AccountList readAccounts() {
        Yaml yaml = new Yaml(new Constructor(AccountList.class));
        try (InputStream in = new FileSystemResource(YAML_PATH).getInputStream()) {
            return yaml.loadAs(in, AccountList.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read YAML", e);
        }
    }

    public static void writeAccounts(AccountList accountList) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        Yaml yaml = new Yaml(new Representer(), options);
        try (FileWriter writer = new FileWriter(YAML_PATH)) {
            yaml.dump(accountList, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write YAML", e);
        }
    }

    public static void updateToken(String accountName, String newToken) {
        AccountList accountList = readAccounts();
        List<Account> accounts = accountList.getAccounts();
        for (Account account : accounts) {
            if (account.getAccount().equals(accountName)) {
                account.setToken(newToken);
                break;
            }
        }
        writeAccounts(accountList);
    }
}
