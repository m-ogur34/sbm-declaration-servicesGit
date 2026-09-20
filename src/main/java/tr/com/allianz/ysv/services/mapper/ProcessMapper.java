package tr.com.allianz.ysv.services.mapper;

import java.util.List;
import org.mapstruct.Mapper;
import tr.com.allianz.ysv.services.dto.response.ProcessView;
import tr.com.allianz.ysv.services.entity.DeclarationProcess;

@Mapper
public interface ProcessMapper {

    ProcessView toView(DeclarationProcess entity);

    List<ProcessView> toViews(List<DeclarationProcess> entities);
}
