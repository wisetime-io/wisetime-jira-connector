<#assign rowTotalDuration = 0?number>
<#if getDescription()?has_content>${getDescription()}</#if>
<#if getNarrativeType() == "NARRATIVE_AND_TIME_ROW_ACTIVITY_DESCRIPTIONS">
 <#list getTimeRows() as timeRow>
  <#assign rowTotalDuration += timeRow.getDurationSecs()>
  |${timeRow.getActivity()}|${timeRow.getDescription()!"N/A"}|
 </#list>
</#if>
<#if getDescription()?has_content || getNarrativeType() == "NARRATIVE_AND_TIME_ROW_ACTIVITY_DESCRIPTIONS">
 ${'\n'}
Total worked time: ${rowTotalDuration?string.@duration}
Total chargeable time: ${getTotalDurationSecs()?string.@duration}
Experience factor: ${getUser().getExperienceWeightingPercent()}%
</#if>


