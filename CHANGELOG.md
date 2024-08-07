# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]

## 0.4.97 (2024-07-02)

- Fix facts with nil values issue

## 0.4.3 (2020-1-8)

- Fix protocol indexing for ClojureScript files

## 0.4.2 (2020-1-4)

- Add :function/args to protocol functions
- Upgrade to datascript 0.18.8

## 0.4.1 (2019-12-12)

- Fix defmethod-facts and prevent indexer crashing when form-facts crashes for a form

## 0.4.0 (2019-12-11)

- [BREAKING] Schema :[function,multimethod,spec.alpha,fspec.alpha]/:source-form collapsed into :source/form, same with :source/str
- Fix multimethod methods facts to correctly link methods with its multi definition

## 0.3.3 (2019-12-09)

- Throw when indexing if org.clojure/tools.namespace is detected on the classpath
- Fix using other than standard repositories for leiningen and deps.edn
- Prevent nil value facts from crashing clindex, report them as warnings

## 0.3.3 (2019-12-07)

- Upgrades tools.deps.alpha and tools.analyzer.jvm

## 0.3.2 (2019-12-02)

- Adds defonce to *def-public-set*

## 0.3.1 (2019-11-30)

- Prevent crashing when can't resolve a fspec symbol namespace

## 0.3.0 (2019-11-27)

- Performance improvement (indexer) from 30s to 2s
- Performance improvement (scanner) from 14s to 0.7s

## 0.2.5 (2019-11-21)

- Add spec indexing, (see schema)
- Add :namespace/depends to the index
- Fix scanning and indexing of vars defined like core/def, clojure.core/def, etc + clojurescript multimethods

## 0.2.4 (2019-11-15)

- Fix ::keyword reading
- Add clindex.api/db-schema
- Add :var/[column end-column] and :var-ref/[column end-column] to the schema
- Fix scanner read-namespace-forms for non list forms
- Honor :mvn/repos on project

## 0.2.3 (2019-10-31)

- Don't add to the tracker namespaces inside jars (tracking cljs compiler doesn't work because of dep cycle)

## 0.2.2 (2019-10-31)

- Index :namespace/docstring, :var/docstring and :function/args

## 0.2.1 (2019-10-29)
