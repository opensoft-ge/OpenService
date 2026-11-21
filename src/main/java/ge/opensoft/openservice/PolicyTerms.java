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

  // Seed concepts are ordinary service-desk vocabulary. Published terms take precedence.
  static Map<String,Object> vocabulary(Map<String,Object> published) {
    var combined = new TreeMap<String,Object>();
    for (var group : List.of(
        List.of("vpn","virtual private network","encrypted tunnel","remote access","telework","telecommuting","offsite","secure connection","remote working"),
        List.of("buy","purchase","purchasing","procure","procurement","requisition","ordering"),
        List.of("parental","parenthood","parenting","paternity","maternity","childbirth","adoption"),
        List.of("network", "connect","connecting","connectivity","networking","network connection","intranet"),
        List.of("broken", "hardware failure","hardware fault","power failure","shuts down","shutting down",
            "powers off","powering off","restarts unexpectedly","no power","will not boot"),
        List.of("agreement","agreements","contracts","contract"),
        List.of("equipment","machines","workstations","workstation"))) {
      for (String phrase : group) combined.put(phrase, published.getOrDefault(group.getFirst(),group.getFirst()));
    }
    combined.putAll(published);
    return combined;
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
    var plurals = new HashSet<String>();
    for (String token : terms)
      if (token.length()>4 && token.endsWith("s") && !token.endsWith("ss"))
        plurals.add(token.substring(0, token.length()-1));
    terms.addAll(plurals);
    aliases.forEach(
        (phrase, concept) -> {
          if (normalized.contains(" " + phrase + " ")) terms.add("concept/" + concept);
        });
    return terms;
  }
}
