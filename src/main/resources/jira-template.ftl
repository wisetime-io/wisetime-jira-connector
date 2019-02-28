<#if getDescription()?has_content>${getDescription()}</#if>

<#if getNarrativeType() == "NARRATIVE_AND_TIME_ROW_ACTIVITY_DESCRIPTIONS">
 <#list getTimeRows() as timeRow>
  |${timeRow.getActivity()}|${timeRow.getDescription()!"N/A"}|
 </#list>
</#if>

Applied experience weighting: ${getUser().getExperienceWeightingPercent()}%
