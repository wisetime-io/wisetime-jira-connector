-- A schema dump of selected tables from Jira v7.12.3, taken on 5th December 2018

create table if not exists project
(
	ID decimal(18) not null
		primary key,
	pname varchar(255) null,
	URL varchar(255) null,
	LEAD varchar(255) null,
	DESCRIPTION text null,
	pkey varchar(255) null,
	pcounter decimal(18) null,
	ASSIGNEETYPE decimal(18) null,
	AVATAR decimal(18) null,
	ORIGINALKEY varchar(255) null,
	PROJECTTYPE varchar(255) null
)
;

create table if not exists jiraissue
(
	ID decimal(18) not null
		primary key,
	pkey varchar(255) null,
	issuenum decimal(18) null,
	PROJECT decimal(18) null,
	REPORTER varchar(255) null,
	ASSIGNEE varchar(255) null,
	CREATOR varchar(255) null,
	issuetype varchar(255) null,
	SUMMARY varchar(255) null,
	DESCRIPTION longtext null,
	ENVIRONMENT longtext null,
	PRIORITY varchar(255) null,
	RESOLUTION varchar(255) null,
	issuestatus varchar(255) null,
	CREATED datetime null,
	UPDATED datetime null,
	DUEDATE datetime null,
	RESOLUTIONDATE datetime null,
	VOTES decimal(18) null,
	WATCHES decimal(18) null,
	TIMEORIGINALESTIMATE decimal(18) null,
	TIMEESTIMATE decimal(18) null,
	TIMESPENT decimal(18) null,
	WORKFLOW_ID decimal(18) null,
	SECURITY decimal(18) null,
	FIXFOR decimal(18) null,
	COMPONENT decimal(18) null
)
;

create table if not exists cwd_user
(
	ID decimal(18) not null
		primary key,
	directory_id decimal(18) null,
	user_name varchar(255) null,
	lower_user_name varchar(255) null,
	active decimal(9) null,
	created_date datetime null,
	updated_date datetime null,
	first_name varchar(255) null,
	lower_first_name varchar(255) null,
	last_name varchar(255) null,
	lower_last_name varchar(255) null,
	display_name varchar(255) null,
	lower_display_name varchar(255) null,
	email_address varchar(255) null,
	lower_email_address varchar(255) null,
	CREDENTIAL varchar(255) null,
	deleted_externally decimal(9) null,
	EXTERNAL_ID varchar(255) null
)
;

create table if not exists worklog
(
	ID decimal(18) not null
		primary key,
	issueid decimal(18) null,
	AUTHOR varchar(255) null,
	grouplevel varchar(255) null,
	rolelevel decimal(18) null,
	worklogbody longtext null,
	CREATED datetime null,
	UPDATEAUTHOR varchar(255) null,
	UPDATED datetime null,
	STARTDATE datetime null,
	timeworked decimal(18) null
)
;

create table if not exists sequence_value_item
(
	SEQ_NAME varchar(60) not null
		primary key,
	SEQ_ID decimal(18) null
)
;