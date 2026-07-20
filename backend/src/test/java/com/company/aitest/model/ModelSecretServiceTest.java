package com.company.aitest.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.company.aitest.common.BusinessException;
import org.junit.jupiter.api.Test;

class ModelSecretServiceTest {

    @Test
    void encryptsModelKeyWithDedicatedKey() {
        ModelSecretService service = new ModelSecretService("model-key", "app-key");

        String protectedValue = service.protect("sk-provider-secret-value");

        org.junit.jupiter.api.Assertions.assertTrue(protectedValue.startsWith("enc:v1:"));
        assertEquals("sk-provider-secret-value", service.reveal(protectedValue));
    }

    @Test
    void fallsBackToApplicationKeyInsteadOfPersistingPlaintext() {
        ModelSecretService service = new ModelSecretService("", "app-key");

        String protectedValue = service.protect("sk-provider-secret-value");

        org.junit.jupiter.api.Assertions.assertTrue(protectedValue.startsWith("enc:v1:"));
        assertEquals("sk-provider-secret-value", service.reveal(protectedValue));
    }

    @Test
    void rejectsSavingAProviderKeyWithoutAnyEncryptionKey() {
        ModelSecretService service = new ModelSecretService("", "");

        assertThrows(BusinessException.class, () -> service.protect("sk-provider-secret-value"));
    }
}
