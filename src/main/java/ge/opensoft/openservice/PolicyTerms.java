package ge.opensoft.openservice;

import static ge.opensoft.openservice.EnterpriseData.*;
import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;

import java.util.*;

final class PolicyTerms {
  private PolicyTerms() {}

  static Object publish(Store.Db db, Map<String, Object> body) throws Exception {
    var aliases = new TreeMap<String, String>();
    for (Object entry : batch(body.get("entries"), 80)) {
      var phrases = batch(entry, 16);
      require(phrases.size() >= 2, "Supply at least two equivalent terms");
      var canonical = EmployeeAssistant.normalized(WorkJobs.text(phrases.getFirst(), 80)).strip();
      for (Object raw : phrases) {
        String phrase = EmployeeAssistant.normalized(WorkJobs.text(raw, 80)).strip();
        require(
            phrase.length() >= 2 && aliases.putIfAbsent(phrase, canonical) == null,
            "Use distinct, nonempty terms across groups");
      }
    }
    return DeliveryWorkspace.put(
        db, "search-dictionary", "current", body.get("revision"), map("aliases", aliases));
  }

  static Set<String> terms(String text, Map<String, Object> aliases) {
    String normalized = EmployeeAssistant.normalized(text);
    var terms = ServiceSearch.words(text);
    terms.removeAll(
        Set.of(
            "policy",
            "which",
            "who",
            "when",
            "our",
            "where",
            "we",
            "how",
            "off",
            "after",
            "someone",
            "whenever",
            "are",
            "was",
            "with",
            "before",
            "does",
            "there",
            "form",
            "that",
            "submit",
            "help",
            "about",
            "on",
            "new",
            "through",
            "or",
            "so",
            "at",
            "has",
            "be",
            "will",
            "in",
            "as",
            "am",
            "by",
            "this",
            "you"));
    aliases.forEach(
        (phrase, concept) -> {
          if (normalized.contains(" " + phrase + " ")) terms.add("concept/" + concept);
        });
    return terms;
  }
}
