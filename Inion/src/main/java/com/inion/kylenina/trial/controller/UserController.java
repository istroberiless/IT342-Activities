package com.inion.kylenina.trial.controller;

import com.inion.kylenina.trial.Service.GoogleContactsService;
import com.google.api.services.people.v1.model.EmailAddress;
import com.google.api.services.people.v1.model.Name;
import com.google.api.services.people.v1.model.Person;
import com.google.api.services.people.v1.model.PhoneNumber;

import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class UserController {
    @Autowired
    private GoogleContactsService googleContactsService;

    @Autowired
    private OAuth2AuthorizedClientService authorizedClientService;

    @GetMapping("")
    public String index() {
        return "home";  // Return home view
    }

    @GetMapping("/user-info")
    public String getUserInfo(@AuthenticationPrincipal Object principal, Model model) {
        String name = null;
        String email = null;
        String pictureUrl = null;

        if (principal instanceof OidcUser) {
            OidcUser oidcUser = (OidcUser) principal;
            name = oidcUser.getFullName();
            email = oidcUser.getEmail();
            pictureUrl = oidcUser.getPicture();

        } else if (principal instanceof OAuth2User) {
            OAuth2User oauth2User = (OAuth2User) principal;
            name = oauth2User.getAttribute("name");
            email = oauth2User.getAttribute("email");
            pictureUrl = oauth2User.getAttribute("picture");

            System.out.println("OAuth2User attributes: " + oauth2User.getAttributes());
        } else {
            return "redirect:/";
        }

        // Set default values for null attributes
        model.addAttribute("name", name != null ? name : "User");
        model.addAttribute("email", email != null ? email : "");
        model.addAttribute("pictureUrl", pictureUrl);

        return "user-info";
    }

    @GetMapping("/contacts")
    public String fetchContactsFromGoogle(Model model, @AuthenticationPrincipal OAuth2User principal) throws IOException {
        if (principal == null) {
            return "redirect:/";
        }

        List<Person> connections = googleContactsService.getConnectionsAsPeople(principal);
        model.addAttribute("contacts", connections);
        return "contact";
    }

    @GetMapping("/contact/add-form")
    public String showAddContactForm(Model model) {
        return "addContact";
    }

    @PostMapping("/contact/add")
    public String addContact(
            @RequestParam("displayName") String name,
            @RequestParam String email,
            @RequestParam(required = false) String phoneNumber,
            @AuthenticationPrincipal OAuth2User principal,
            Model model) {

        System.out.println("Adding contact: " + name + ", " + email + ", " + phoneNumber);

        try {
            googleContactsService.addContact(principal, name, email, phoneNumber);
            return "redirect:/contacts";
        } catch (Exception e) {
            model.addAttribute("error", "Failed to add contact: " + e.getMessage());
            return "addContact";
        }
    }

    @GetMapping("/contacts/edit/people/{contactId}")
    public String editContactForm(
            @PathVariable String contactId,
            @AuthenticationPrincipal OAuth2User principal,
            Model model) {

        try {
            // Fetch the contact by ID
            Person contact = googleContactsService.getPersonById(principal, "people/" + contactId);

            if (contact == null) {
                throw new RuntimeException("Contact not found.");
            }

            // Add the contact to the model
            model.addAttribute("contact", contact);

            // Return the edit form template
            return "editContact";

        } catch (RuntimeException e) {
            // Log the error and add an error message to the model
            model.addAttribute("error", "Failed to load contact: " + e.getMessage());
            return "redirect:/contacts"; // Redirect to the contacts list with an error message
        }
    }

    // POST: Handle the form submission
    @PostMapping("/contacts/edit/people/{contactId}")
    public String updateContact(
            @PathVariable String contactId,
            @RequestParam String displayName,
            @RequestParam String email,
            @RequestParam String phoneNumber,
            @AuthenticationPrincipal OAuth2User principal,
            Model model) {

        try {
            // Fetch the contact to get the current etag
            Person existingContact = googleContactsService.getPersonById(principal, "people/" + contactId);
            if (existingContact == null) {
                throw new RuntimeException("Contact not found.");
            }

            // Create a person for update
            Person updatePerson = new Person();
            updatePerson.setEtag(existingContact.getEtag()); // Set the etag

            // Update name if provided
            if (displayName != null && !displayName.isEmpty()) {
                Name personName = new Name();
                personName.setDisplayName(displayName);
                personName.setGivenName(displayName);
                updatePerson.setNames(Arrays.asList(personName));
            }

            // Update email if provided
            if (email != null && !email.isEmpty()) {
                EmailAddress emailAddress = new EmailAddress();
                emailAddress.setValue(email);
                emailAddress.setType("home");
                updatePerson.setEmailAddresses(Arrays.asList(emailAddress));
            }

            // Update phone if provided
            if (phoneNumber != null && !phoneNumber.isEmpty()) {
                PhoneNumber personPhone = new PhoneNumber();
                personPhone.setValue(phoneNumber);
                personPhone.setType("mobile");
                updatePerson.setPhoneNumbers(Arrays.asList(personPhone));
            }

            // Determine which fields to update
            List<String> updatePersonFields = new ArrayList<>();
            if (displayName != null && !displayName.isEmpty()) updatePersonFields.add("names");
            if (email != null && !email.isEmpty()) updatePersonFields.add("emailAddresses");
            if (phoneNumber != null && !phoneNumber.isEmpty()) updatePersonFields.add("phoneNumbers");

            // Validate that at least one field is being updated
            if (updatePersonFields.isEmpty()) {
                throw new RuntimeException("No fields provided for update.");
            }

            // Perform the update
            googleContactsService.updateContact(principal, "people/" + contactId, updatePerson, updatePersonFields);

            return "redirect:/contacts"; // Redirect to contacts list after successful update

        } catch (RuntimeException e) {
            model.addAttribute("error", "Failed to update contact: " + e.getMessage());
            return "editContact"; // Return to the edit page with an error message
        }
    }

    @DeleteMapping("/contacts/delete/{contactId}")
    @ResponseBody
    public ResponseEntity<Map<String, String>> deleteContactAjax(
            @PathVariable String contactId,
            @AuthenticationPrincipal OAuth2User principal) {
        Map<String, String> response = new HashMap<>();
        try {
            googleContactsService.deleteContact(principal, "people/" + contactId);
            response.put("status", "success");
            response.put("message", "Contact deleted successfully");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    @GetMapping("/contacts/delete/people/{contactId}")
    public String deleteContact(
            @PathVariable String contactId,
            @AuthenticationPrincipal OAuth2User principal) {
        try {
            googleContactsService.deleteContact(principal, "people/" + contactId);
        } catch (Exception e) {
            System.err.println("Error deleting contact: " + e.getMessage());
        }
        return "redirect:/contacts";
    }
}