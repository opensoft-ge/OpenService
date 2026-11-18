package ge.opensoft.openservice;

import static ge.opensoft.openservice.TypedFields.require;
import static ge.opensoft.openservice.Values.*;
import java.sql.SQLException;
import java.util.*;

/** Bounded native questions and immutable submitted answers; no executable catalogue scripts. */
final class CatalogueQuestions {
    private CatalogueQuestions() {}

    static Map<String,Object> decode(Map<String,Object> offering) {
        offering.put("questions",JSON.readValue(offering.get("questions").toString(),Object.class));return offering;
    }

    static List<Map<String,Object>> validate(Object input) {
        require(input instanceof List<?> items && items.size()<=20,"Use at most 20 questions");
        var result=new ArrayList<Map<String,Object>>();var keys=new HashSet<String>();
        for(Object raw:(List<?>)input){
            var q=object(raw);require(q.keySet().containsAll(Set.of("key","label","type","required","choices")) && Set.of("key","label","type","required","choices","when").containsAll(q.keySet()),"Provide key, label, type, required and choices");
            String key=WorkJobs.text(q.get("key"),40),label=WorkJobs.text(q.get("label"),160),type=WorkJobs.text(q.get("type"),20);
            require(key.matches("[a-z][a-z0-9_]{0,39}") && keys.add(key),"Use unique question keys with lowercase letters, digits and underscores");
            require(Set.of("string","choice","boolean","date","integer").contains(type),"Choose text, choice, yes/no, date or integer");
            require(q.get("required") instanceof Boolean,"Required must be boolean");
            require(q.get("choices") instanceof List<?> options && options.size()<=20,"Use at most 20 choices");
            var choices=new ArrayList<String>();for(Object option:(List<?>)q.get("choices")){String choice=WorkJobs.text(option,120);require(!choices.contains(choice),"Choices must be unique");choices.add(choice);}
            require(type.equals("choice")?!choices.isEmpty():choices.isEmpty(),"Choice questions need options; other types must have none");
            var normalized=map("key",key,"label",label,"type",type,"required",q.get("required"),"choices",choices);CatalogueConditions.validate(q,normalized,result);result.add(normalized);
        }
        return result;
    }

    static Map<String,Object> save(Store.Db db,String actor,String id,Object input) throws SQLException {
        Service.requireAccountAdministrator(db,actor);if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");Catalogue.find(db,id,true);var body=object(input);
        require(body.keySet().equals(Set.of("revision","questions")),"Provide revision and questions");require(body.get("revision") instanceof Integer n && n>0,"Use a positive offering revision");var questions=validate(body.get("questions"));
        if(db.update("UPDATE os_offering SET questions=?,revision=revision+1 WHERE offering_id=? AND revision=?",JSON.writeValueAsString(questions),id,body.get("revision"))!=1)throw new Failure(409,"Offering changed; reload before editing questions");
        SecurityEvents.record(db,actor,actor,"catalogue.questions_changed",map("offering_id",id,"question_count",questions.size()));return Catalogue.find(db,id,true);
    }

    static Map<String,Object> editor(Map<String,Object> offering,String key) {
        if(key.isEmpty())return map("key","","label","","type","string","required",false,"choices",List.of());
        for(var question:objects(offering.get("questions")))if(question.get("key").equals(key))return question;
        throw new Failure(404,"Question not found");
    }

    static Map<String,Object> edit(Store.Db db,String actor,String id,Map<String,Object> form) throws SQLException {
        Service.requireAccountAdministrator(db,actor);if(db.postgres)db.all("SELECT pg_advisory_xact_lock(18439,4)");var offering=Catalogue.find(db,id,true);String operation=WorkJobs.text(form.get("operation"),10);
        require(Set.of("save","remove").contains(operation),"Choose save or remove");boolean remove=operation.equals("remove");
        var fields=new HashSet<>(remove?Set.of("operation","revision","key"):Set.of("operation","revision","key","label","type","required","choices"));if(!remove && form.containsKey("condition_key"))fields.addAll(Set.of("condition_key","condition_value"));require(form.keySet().equals(fields),"Provide the question form fields");
        String key=WorkJobs.text(form.get("key"),40);var questions=new ArrayList<>(objects(offering.get("questions")));int index=-1;for(int i=0;i<questions.size();i++)if(questions.get(i).get("key").equals(key))index=i;
        if(remove){if(index<0)throw new Failure(404,"Question not found");questions.remove(index);}
        else{
            require(Set.of("true","false").contains(form.get("required")),"Choose required or optional");require(form.get("choices") instanceof String,"Choices must be text");String options=form.get("choices").toString();
            var question=map("key",key,"label",form.get("label"),"type",form.get("type"),"required",Boolean.parseBoolean(form.get("required").toString()),"choices",options.isBlank()?List.of():Arrays.asList(options.split("\\R",-1)));
            CatalogueConditions.form(form,question);if(index<0)questions.add(question);else questions.set(index,question);
        }
        return save(db,actor,id,map("revision",form.get("revision"),"questions",questions));
    }

    static Map<String,Object> input(Object input) {
        var values=object(input);require(values.size()<=20,"Use at most 20 answers");var sorted=new TreeMap<String,Object>();
        for(var value:values.entrySet()){
            require(value.getKey().matches("[a-z][a-z0-9_]{0,39}"),"Use a question key");require(value.getValue() instanceof String,"Answers must be text");String answer=value.getValue().toString();require(answer.length()<=2000 && answer.indexOf('\0')<0,"Answers must be at most 2000 characters without NUL");sorted.put(value.getKey(),answer);
        }
        return new LinkedHashMap<>(sorted);
    }

    static Object form(Map<String,Object> form) {
        var answers=map();for(String key:new ArrayList<>(form.keySet()))if(key.startsWith("answer."))answers.put(key.substring(7),form.remove(key));
        if(!answers.isEmpty())form.put("answers",answers);return form;
    }

    static List<Map<String,Object>> snapshot(Store.Db db,Map<String,Object> offering,Map<String,Object> answers) throws SQLException {
        var all=objects(offering.get("questions"));var keys=new HashSet<String>();for(var q:all)keys.add(q.get("key").toString());require(keys.containsAll(answers.keySet()),"One or more answers do not belong to this offering");var active=CatalogueConditions.active(all,answers);var rules=map();for(var q:active)rules.put(q.get("key").toString(),map("type",q.get("type"),"required",q.get("required"),"max_length",2000,"choices",q.get("choices")));
        TypedFields.check(db,map("field_rules",rules),answers);
        var snapshot=new ArrayList<Map<String,Object>>();for(var q:active)snapshot.add(map("key",q.get("key"),"label",q.get("label"),"type",q.get("type"),"value",answers.getOrDefault(q.get("key"),"")));return snapshot;
    }

    static String description(List<Map<String,Object>> answers) {
        if(answers.isEmpty())return "";var lines=new ArrayList<String>();for(var answer:answers)lines.add(answer.get("label")+": "+answer.get("value"));return "\n\nSubmitted answers:\n"+String.join("\n",lines);
    }

    static List<Map<String,Object>> answers(Store.Db db,String request) throws SQLException {
        Service.record(db,"sc_request",request);if(!FieldAccess.allowed(db,Service.schema(db,"sc_request"),"description"))throw new Failure(403,"Request description read access is required for submitted answers");
        var row=db.one("SELECT answers FROM os_catalog_order WHERE request_id=?",request);return row==null?List.of():objects(JSON.readValue(row.get("answers").toString(),Object.class));
    }
}
